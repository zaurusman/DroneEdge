# File MediaCodec Decoder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the file source's ~6 fps `MediaMetadataRetriever` decode with a MediaCodec → Surface + PixelCopy pipeline so inference reaches its true throughput and recordings are smooth and full-resolution.

**Architecture:** A new `FileMediaCodecVideoSource` decodes the file with `MediaExtractor` + `MediaCodec` rendering to the on-screen `Surface` (full-res, GPU, auto-rotation), paced to 1× real time and looping via `seekTo(0)`. A PixelCopy loop captures full-resolution ARGB frames for inference + recording. `LiveScreen` swaps the live file preview from ExoPlayer to a `SurfaceView`; ExoPlayer stays for saved-file playback.

**Tech Stack:** Kotlin, Coroutines/Flow (`channelFlow`), `android.media.MediaExtractor`/`MediaCodec`, `android.view.PixelCopy`, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-16-file-mediacodec-decoder-design.md`

---

## File Structure

- **Create** `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt` — the new source + two pure companion helpers.
- **Create** `app/src/test/java/com/yotam/droneedge/video/FileMediaCodecVideoSourceTest.kt` — unit tests for the pure helpers.
- **Modify** `LiveViewModel.kt` — rename surface wiring to a generic name, construct the new source, recreate it with the surface at `start()`.
- **Modify** `LiveScreen.kt` — generic `RenderSurfaceView`, use it for the file branch.
- **Remove (Task 7, after on-device validation)** `FileReplayVideoSource.kt`.

---

### Task 1: Pure helpers (rotation + pacing) with tests

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt`
- Test: `app/src/test/java/com/yotam/droneedge/video/FileMediaCodecVideoSourceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/yotam/droneedge/video/FileMediaCodecVideoSourceTest.kt`:

```kotlin
package com.droneedge.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMediaCodecVideoSourceTest {

    @Test
    fun rotatedDimensions_keepsDimensionsForUprightRotations() {
        assertEquals(1920 to 1080, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 0))
        assertEquals(1920 to 1080, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 180))
    }

    @Test
    fun rotatedDimensions_swapsDimensionsForSidewaysRotations() {
        assertEquals(1080 to 1920, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 90))
        assertEquals(1080 to 1920, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 270))
    }

    @Test
    fun pacingDelayMs_returnsRemainingMillisUntilPresentationTime() {
        // anchor: wall=1_000_000_000ns at pts=0us. Frame pts=100_000us (100ms).
        // now=1_040_000_000ns (40ms elapsed) -> 60ms remaining.
        val delay = FileMediaCodecVideoSource.pacingDelayMs(
            anchorWallNs = 1_000_000_000L,
            anchorPtsUs = 0L,
            presentationTimeUs = 100_000L,
            nowNs = 1_040_000_000L,
        )
        assertEquals(60L, delay)
    }

    @Test
    fun pacingDelayMs_negativeWhenBehindSchedule() {
        val delay = FileMediaCodecVideoSource.pacingDelayMs(
            anchorWallNs = 1_000_000_000L,
            anchorPtsUs = 0L,
            presentationTimeUs = 10_000L,      // 10ms target
            nowNs = 1_050_000_000L,            // 50ms elapsed -> 40ms behind
        )
        assertEquals(-40L, delay)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.video.FileMediaCodecVideoSourceTest"`
Expected: FAIL — `FileMediaCodecVideoSource` unresolved.

- [ ] **Step 3: Create the file with the pure helpers**

Create `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt`:

```kotlin
package com.droneedge.app.video

import android.content.Context
import android.net.Uri
import android.view.Surface

/**
 * Decodes a local video file with MediaExtractor + MediaCodec, rendering to a display
 * Surface (full-res, GPU, auto-rotated) and capturing full-resolution frames via PixelCopy
 * for inference + recording. Replaces the ~6 fps MediaMetadataRetriever path.
 */
class FileMediaCodecVideoSource(
    private val uri: Uri,
    private val context: Context,
    private val renderSurface: Surface? = null,
) : VideoSource {

    @Volatile override var width: Int = 0
        private set
    @Volatile override var height: Int = 0
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    // frames flow added in Task 2.
    override val frames: kotlinx.coroutines.flow.Flow<VideoFrame> =
        kotlinx.coroutines.flow.emptyFlow()

    companion object {
        /** Display dimensions after applying the file's rotation metadata. */
        fun rotatedDimensions(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
            if (rotation == 90 || rotation == 270) height to width else width to height

        /** Milliseconds to wait so the frame at [presentationTimeUs] renders at 1× real time. */
        fun pacingDelayMs(
            anchorWallNs: Long,
            anchorPtsUs: Long,
            presentationTimeUs: Long,
            nowNs: Long,
        ): Long {
            val targetWallNs = anchorWallNs + (presentationTimeUs - anchorPtsUs) * 1_000L
            return (targetWallNs - nowNs) / 1_000_000L
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.video.FileMediaCodecVideoSourceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt app/src/test/java/com/yotam/droneedge/video/FileMediaCodecVideoSourceTest.kt
git commit -m "feat: FileMediaCodecVideoSource pure helpers (rotation, pacing) + tests"
```

---

### Task 2: Decode + display + PixelCopy capture (the frames flow)

This is integration code (MediaCodec/Surface/PixelCopy) — not unit-testable; verified on device in Task 5.

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt`

- [ ] **Step 1: Add dimension probe in `init` and the imports**

Replace the import block and add an `init` block. The file's top becomes:

```kotlin
package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
```

Add this `init` block right after the `running`/`frameIndex` fields:

```kotlin
    init {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = selectVideoTrack(extractor)
            val fmt = extractor.getTrackFormat(track)
            val rawW = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val rawH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (fmt.containsKey(MediaFormat.KEY_ROTATION))
                fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (w, h) = rotatedDimensions(rawW, rawH, rot)
            width = w
            height = h
        } finally {
            extractor.release()
        }
    }
```

- [ ] **Step 2: Add `selectVideoTrack` to the companion object**

Inside `companion object`, add:

```kotlin
        /** Index of the first video track, or throws if none. */
        fun selectVideoTrack(extractor: MediaExtractor): Int {
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return i
            }
            error("No video track in $extractor")
        }
```

- [ ] **Step 3: Replace the placeholder `frames` flow with the real decode loop**

Replace:

```kotlin
    override val frames: kotlinx.coroutines.flow.Flow<VideoFrame> =
        kotlinx.coroutines.flow.emptyFlow()
```

with:

```kotlin
    override val frames: Flow<VideoFrame> = channelFlow {
        // Full-res PixelCopy capture of the rendered surface → inference + recording.
        val pending = AtomicReference<Bitmap?>(null)
        val captureJob = if (renderSurface != null) launch(Dispatchers.IO) {
            val ht = HandlerThread("file-pixelcopy").also { it.start() }
            val handler = Handler(ht.looper)
            val capBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                while (isActive) {
                    delay(15L) // ≤~60fps; actual rate bounded by decode/display
                    suspendCancellableCoroutine<Unit> { cont ->
                        PixelCopy.request(renderSurface, capBitmap, { res ->
                            if (res == PixelCopy.SUCCESS) {
                                pending.getAndSet(capBitmap.copy(Bitmap.Config.ARGB_8888, false))
                                    ?.recycle()
                            }
                            cont.resume(Unit)
                        }, handler)
                    }
                }
            } finally {
                capBitmap.recycle()
                pending.getAndSet(null)?.recycle()
                ht.quitSafely()
            }
        } else null

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = selectVideoTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, renderSurface, null, 0)
        codec.start()

        val bufInfo = MediaCodec.BufferInfo()
        var anchorWallNs = System.nanoTime()
        var anchorPtsUs = -1L

        try {
            while (running && isActive) {
                // ── Feed one input buffer ──
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) {
                        // End of file → seamless loop back to start, re-anchor pacing.
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        anchorPtsUs = -1L
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        extractor.advance()
                    }
                }

                // ── Drain one output buffer, pace, render ──
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // dims set in init
                    outIdx >= 0 -> {
                        if (anchorPtsUs < 0L) {
                            anchorPtsUs = bufInfo.presentationTimeUs
                            anchorWallNs = System.nanoTime()
                        }
                        val sleep = pacingDelayMs(
                            anchorWallNs, anchorPtsUs, bufInfo.presentationTimeUs, System.nanoTime()
                        )
                        if (sleep in 1L..200L) delay(sleep)
                        codec.releaseOutputBuffer(outIdx, true) // render to surface
                        val bmp = pending.getAndSet(null)
                        send(VideoFrame(frameIndex++, System.currentTimeMillis(), width, height, bmp))
                    }
                }
            }
        } finally {
            captureJob?.cancel()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
    }.flowOn(Dispatchers.IO)
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run unit tests (helpers still pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.video.FileMediaCodecVideoSourceTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt
git commit -m "feat: FileMediaCodecVideoSource decode→surface + PixelCopy capture + 1x pacing/loop"
```

---

### Task 3: Wire the new source into `LiveViewModel`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Rename the surface field to a generic name**

Replace lines 71–73:

```kotlin
    // ── Surface for DJI GPU-direct rendering ─────────────────────────────────
    private val _djiSurface = MutableStateFlow<android.view.Surface?>(null)
    fun setDjiSurface(surface: android.view.Surface?) { _djiSurface.value = surface }
```

with:

```kotlin
    // ── Display surface for surface-based sources (DJI + file MediaCodec) ──────
    private val _renderSurface = MutableStateFlow<android.view.Surface?>(null)
    fun setRenderSurface(surface: android.view.Surface?) { _renderSurface.value = surface }
```

- [ ] **Step 2: Update the DJI `start()` reference to the renamed field**

In `start()` (around line 456) change:

```kotlin
                renderSurface  = _djiSurface.value,
```

to:

```kotlin
                renderSurface  = _renderSurface.value,
```

- [ ] **Step 3: Construct the new file source in `useFileSource`**

Replace line 157:

```kotlin
        videoSource      = FileReplayVideoSource(uri, context.applicationContext)
```

with:

```kotlin
        videoSource      = FileMediaCodecVideoSource(uri, context.applicationContext)
```

Add the import near the other video imports:

```kotlin
import com.droneedge.app.video.FileMediaCodecVideoSource
```

- [ ] **Step 4: Recreate the file source with the surface at `start()`**

In `start()`, immediately after the existing `_djiAccessory.value?.let { ... }` block (around line 458), add:

```kotlin
        // For a file source, re-create it with the display Surface that Compose has set up
        // by the time the user presses START (mirrors the DJI accessory path).
        _videoUri.value?.let { uri ->
            videoSource = com.droneedge.app.video.FileMediaCodecVideoSource(
                uri           = uri,
                context       = getApplication<android.app.Application>().applicationContext,
                renderSurface = _renderSurface.value,
            )
        }
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Expect an "unused" note for `FileReplayVideoSource` until Task 7 — fine.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "feat: wire FileMediaCodecVideoSource + generic render surface in LiveViewModel"
```

---

### Task 4: Swap the live file preview to a SurfaceView in `LiveScreen`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Generalize `DjiSurfaceView` → `RenderSurfaceView`**

Replace the `DjiSurfaceView` composable (lines 674–691):

```kotlin
@Composable
private fun DjiSurfaceView(
    modifier: Modifier = Modifier,
    onSurface: (android.view.Surface?) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) = onSurface(holder.surface)
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = onSurface(null)
                })
            }
        },
    )
}
```

with (rename only):

```kotlin
@Composable
private fun RenderSurfaceView(
    modifier: Modifier = Modifier,
    onSurface: (android.view.Surface?) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) = onSurface(holder.surface)
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = onSurface(null)
                })
            }
        },
    )
}
```

- [ ] **Step 2: Use the SurfaceView for the file branch and the renamed setter for DJI**

Replace the display `when` block (lines 304–319):

```kotlin
        when {
            videoUri != null -> VideoPlayer(
                uri       = videoUri!!,
                isPlaying = sessionState == SessionState.RUNNING,
                modifier  = Modifier.fillMaxSize(),
            )
            djiDevice != null || djiAccessory != null -> DjiSurfaceView(
                modifier  = Modifier.fillMaxSize(),
                onSurface = { vm.setDjiSurface(it) },
            )
            cameraFacing != null -> CameraFrameDisplay(
                frames   = vm.latestFrame,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(modifier = Modifier.fillMaxSize().background(FieldBackground))
        }
```

with:

```kotlin
        when {
            videoUri != null -> RenderSurfaceView(
                modifier  = Modifier.fillMaxSize(),
                onSurface = { vm.setRenderSurface(it) },
            )
            djiDevice != null || djiAccessory != null -> RenderSurfaceView(
                modifier  = Modifier.fillMaxSize(),
                onSurface = { vm.setRenderSurface(it) },
            )
            cameraFacing != null -> CameraFrameDisplay(
                frames   = vm.latestFrame,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(modifier = Modifier.fillMaxSize().background(FieldBackground))
        }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Compiler will note `VideoPlayer` is now unused in LiveScreen — that's fine; it may be removed in Task 7. ExoPlayer/`VideoPlayer` usage in `RecordingsScreen` is untouched.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: live file preview uses MediaCodec SurfaceView (ExoPlayer stays for playback)"
```

---

### Task 5: On-device verification (emulator + Tab)

No code; this is the integration gate that this architecture works. Record the results.

- [ ] **Step 1: Build + install**

Run: `./gradlew installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 2: Run the app on a video file**

- Pick a video file source, press Start.
- Confirm: video shows **full-resolution and smooth** on screen; detection boxes appear; correct orientation (test a portrait clip too).

- [ ] **Step 3: Check inference fps vs the ~14 target**

- Read the HUD inference fps (should rise well above the old ~5).
- Pull timing: `adb shell run-as com.droneedge.app cat files/../app_flag 2>/dev/null` is not it — read the public log:
  `adb shell "cat /sdcard/Android/data/com.droneedge.app/files/logs/tflite_timing.txt"`
  Note `pre=` (the scale cost) and resulting fps.
- **Decision:** if inference fps is comfortably ≥ ~14, skip Task 6. If it's dragged below target by `pre=`, do Task 6.

- [ ] **Step 4: Record and verify the file**

- Arm Record, run ~5s, Stop.
- Confirm it saves (no "saving…" hang), and the MP4 is **smooth, full-resolution, correctly oriented, correct colours**.
- Confirm the file is not stuck pending:
  `adb shell "find /sdcard/Movies/DroneEdge -name '.pending*' -newermt '-3 minutes'"` → expect empty.

- [ ] **Step 5: Verify loop + clean stop/switch**

- Let the clip reach its end → confirm it loops seamlessly.
- Stop the session, switch to another source and back → confirm no crash and no leaked decoder (logcat has no `MediaCodec` errors).

- [ ] **Step 6: Commit a note (optional)**

If you captured a useful timing/fps note, add it to the spec's verification section and commit.

---

### Task 6: (CONDITIONAL — only if Task 5 Step 3 showed fps below target) Dedicated small inference capture

Add a second, GPU-scaled 640×360 PixelCopy so inference never reads full-res.

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/video/VideoFrame.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add `inferenceBitmap` to `VideoFrame`**

Change the data class to:

```kotlin
data class VideoFrame(
    val index: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap? = null,
    /** Optional small (≈640×360) bitmap for inference; falls back to [bitmap] when null. */
    val inferenceBitmap: Bitmap? = null,
)
```

- [ ] **Step 2: Capture a second small bitmap in the source**

In `FileMediaCodecVideoSource`'s capture job, allocate a second bitmap
`val inferBitmap = Bitmap.createBitmap(640, (640 * height / width), Bitmap.Config.ARGB_8888)`
and a second `AtomicReference<Bitmap?>`, do a second `PixelCopy.request(renderSurface, inferBitmap, …)` in the same loop, and attach it: `send(VideoFrame(frameIndex++, now, width, height, bmp, inferBmp))`. Recycle both in `finally`.

- [ ] **Step 3: Prefer `inferenceBitmap` in the inference loop**

In `LiveViewModel`'s inference coroutine, change:

```kotlin
                    val results = detector.detect(frame)
```

to:

```kotlin
                    val small = frame.inferenceBitmap
                    val results = detector.detect(
                        if (small != null) frame.copy(bitmap = small, width = small.width, height = small.height)
                        else frame
                    )
```

- [ ] **Step 4: Build, install, re-verify fps**

Run: `./gradlew installDebug` then repeat Task 5 Step 3. Expect fps back at ~14.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/VideoFrame.kt app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "perf: dedicated 640x360 inference capture for file source to hold 14fps"
```

---

### Task 7: Remove `FileReplayVideoSource` (after Task 5 passes)

**Files:**
- Delete: `app/src/main/java/com/yotam/droneedge/video/FileReplayVideoSource.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` (remove now-unused `VideoPlayer` composable **only if** nothing else references it; the Recordings playback uses its own player — verify with grep)

- [ ] **Step 1: Confirm no references remain**

Run: `grep -rn "FileReplayVideoSource" app/src`
Expected: no matches (Task 3 replaced the only usage).

- [ ] **Step 2: Delete the file**

```bash
git rm app/src/main/java/com/yotam/droneedge/video/FileReplayVideoSource.kt
```

- [ ] **Step 3: Remove the dead `VideoPlayer` composable if unused in LiveScreen**

Run: `grep -n "VideoPlayer" app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`
If the only hit is its own definition, delete the `VideoPlayer` composable (lines ~695–719) and any now-unused ExoPlayer/PlayerView imports in `LiveScreen.kt`. Do **not** touch `RecordingsScreen`.

- [ ] **Step 4: Full build + tests**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove FileReplayVideoSource (replaced by MediaCodec file source)"
```

---

## Self-Review

- **Spec coverage:** new source (T1–T2), full-res display+capture (T2), surface wiring (T3), SurfaceView display + ExoPlayer kept for playback (T4), rotation (T1 helper + surface auto-rotate in T2), pacing/loop (T2), inference 14 fps (T5 verify + T6 fallback), recording quality (T5 verify), remove old source (T7). All covered.
- **Placeholder scan:** Task 6 Step 2 describes the second-capture edit in prose rather than a full code block because it is conditional and mirrors the Task 2 capture block; if executed, copy the Task 2 capture pattern. All non-conditional steps have full code.
- **Type consistency:** `setRenderSurface`/`_renderSurface` used in T3 and T4; `RenderSurfaceView` defined and used in T4; `rotatedDimensions`/`pacingDelayMs`/`selectVideoTrack` signatures consistent across T1/T2; `VideoFrame.inferenceBitmap` defined (T6.1) before use (T6.3).

## Risks

- PixelCopy timing vs surface validity (mitigated by the DJI-proven pattern + null-safe sends).
- Loop without `codec.flush()` may show a brief artifact at wrap; if so, add `codec.flush()` after `seekTo` and re-anchor.
- Emulator decoder/PixelCopy quirks — Task 5 runs on both emulator and Tab.
