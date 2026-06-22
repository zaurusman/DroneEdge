# DJI Passthrough Recording (Part 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record the DJI stream by muxing the drone's raw H.264 directly to MP4 (full-res, native fps, ~zero live cost), plus the existing `detections.json` — no decode/encode/PixelCopy.

**Architecture:** A new `H264PassthroughRecorder` (a `SessionRecorder`) gets video from encoded NAL units teed off `DjiGogglesAccessorySource`'s existing NAL-reassembly loop (via an `encodedSink`) and writes them to a `MediaMuxer`; it gets detections from the existing `onFrame(...)` calls and writes only the JSON. Shared MediaStore/JSON storage is factored into `RecordingStorage`. `LiveViewModel` selects this recorder for DJI sources and wires the sink.

**Tech Stack:** Kotlin, Coroutines/Channels, `android.media.MediaMuxer`/`MediaCodec.BufferInfo`, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-22-passthrough-recording-gl-calc-design.md`

**Package note:** source dirs are `com/yotam/droneedge/...` but package declarations are `com.droneedge.app.*`. Match the existing files in each directory. Build: `./gradlew :app:compileDebugKotlin`, test: `./gradlew :app:testDebugUnitTest`.

---

## File Structure

- **Create** `recording/RecordingStorage.kt` — MediaStore video file + JSON writer + finalize helpers (extracted from `VideoSessionRecorder`).
- **Create** `video/H264NalParser.kt` — pure NAL-type classification + constants.
- **Create** `recording/H264PassthroughRecorder.kt` — the passthrough recorder.
- **Create** tests: `test/.../video/H264NalParserTest.kt`.
- **Modify** `recording/VideoSessionRecorder.kt` — use `RecordingStorage` (remove its private storage copies).
- **Modify** `video/DjiGogglesAccessorySource.kt` — add `encodedSink` + tee in the NAL loop.
- **Modify** `ui/live/LiveViewModel.kt` — pick `H264PassthroughRecorder` for DJI + wire/unwire the sink.

---

### Task 1: Extract `RecordingStorage`

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/RecordingStorage.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt`

- [ ] **Step 1: Create `RecordingStorage.kt`**

```kotlin
package com.droneedge.app.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/** Shared storage for session recorders: the MediaStore video file + the JSON sidecar. */
object RecordingStorage {

    data class VideoFile(val pfd: ParcelFileDescriptor, val uri: Uri)
    data class JsonFile(val writer: BufferedWriter, val uri: Uri)

    /** Opens `Movies/DroneEdge/<sessionName>/annotated.mp4` for writing (pending on API 29+). */
    fun openVideoFile(context: Context, sessionName: String): VideoFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "annotated.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/$sessionName/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
            ) ?: error("MediaStore insert failed for video")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Cannot open file descriptor for $uri")
            VideoFile(pfd, uri)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "annotated.mp4")
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
            VideoFile(pfd, Uri.fromFile(file))
        }
    }

    /** Opens `<externalFilesDir>/recordings/<sessionName>/detections.json` (adb-accessible). */
    fun openJsonWriter(context: Context, sessionName: String): JsonFile {
        val dir = File(context.getExternalFilesDir(null), "recordings/$sessionName").also { it.mkdirs() }
        val file = File(dir, "detections.json")
        return JsonFile(BufferedWriter(FileWriter(file)), Uri.fromFile(file))
    }

    /** Clears IS_PENDING so the video becomes visible (API 29+). No-op below Q. */
    fun finalizeVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
            )
        }
    }
}
```

- [ ] **Step 2: Refactor `VideoSessionRecorder` to use it**

In `VideoSessionRecorder.kt`, replace the body of `start()`'s file-opening with the shared helpers and delete the private `openVideoFile`/`openJsonWriter`/`finalizeMediaStore` methods.

Replace these lines in `start()`:
```kotlin
            jsonWriter = openJsonWriter(context.applicationContext)
            jsonWriter?.appendLine("""{"sessionStart":$firstTimestampMs}""")

            val pfd = openVideoFile(context.applicationContext)
            videoFd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
```
with:
```kotlin
            val jf = RecordingStorage.openJsonWriter(context.applicationContext, sessionName)
            jsonWriter = jf.writer
            jsonRowUri = jf.uri
            jsonWriter?.appendLine("""{"sessionStart":$firstTimestampMs}""")

            val vf = RecordingStorage.openVideoFile(context.applicationContext, sessionName)
            videoFd = vf.pfd
            videoRowUri = vf.uri
            muxer = MediaMuxer(vf.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
```

In `finalizeMediaStore()`'s body, replace the `contentResolver.update(...)` block with:
```kotlin
    private fun finalizeMediaStore() {
        val ctx = appContext ?: return
        videoRowUri?.let { RecordingStorage.finalizeVideo(ctx, it) }
    }
```
Then delete the now-unused private `openVideoFile(...)` and `openJsonWriter(...)` methods (lines ~267–303). Remove any imports that become unused (`ContentValues`, `Environment`, `FileWriter`, `MediaStore` if no longer referenced — check with the compiler).

- [ ] **Step 3: Build + run existing recording tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; existing tests pass (e.g. `RecordingStateTransitionTest`, `YuvConversionTest`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/RecordingStorage.kt app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
git commit -m "refactor: extract RecordingStorage (shared MediaStore + JSON helpers)"
```

---

### Task 2: `H264NalParser` (pure) + tests

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/H264NalParser.kt`
- Test: `app/src/test/java/com/yotam/droneedge/video/H264NalParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.droneedge.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264NalParserTest {

    @Test fun nalType_threeByteStartCode() {
        // 00 00 01 | 0x65 -> type 5 (IDR): 0x65 & 0x1F = 5
        val nal = byteArrayOf(0, 0, 1, 0x65.toByte(), 0x11, 0x22)
        assertEquals(5, H264NalParser.nalType(nal))
    }

    @Test fun nalType_fourByteStartCode() {
        // 00 00 00 01 | 0x67 -> type 7 (SPS): 0x67 & 0x1F = 7
        val nal = byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42)
        assertEquals(7, H264NalParser.nalType(nal))
    }

    @Test fun nalType_pps() {
        val nal = byteArrayOf(0, 0, 1, 0x68.toByte()) // 0x68 & 0x1F = 8
        assertEquals(8, H264NalParser.nalType(nal))
    }

    @Test fun nalType_returnsMinusOneWhenNoPayload() {
        assertEquals(-1, H264NalParser.nalType(byteArrayOf(0, 0, 1)))
    }

    @Test fun constantsMatchH264() {
        assertEquals(5, H264NalParser.NAL_IDR)
        assertEquals(7, H264NalParser.NAL_SPS)
        assertEquals(8, H264NalParser.NAL_PPS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.video.H264NalParserTest"`
Expected: FAIL — `H264NalParser` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.droneedge.app.video

/** Minimal H.264 Annex-B NAL helpers (start code 00 00 01 or 00 00 00 01). */
object H264NalParser {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** NAL unit type (header byte & 0x1F), or -1 if [nal] has no payload after the start code. */
    fun nalType(nal: ByteArray): Int {
        val h = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> 0
        }
        if (h >= nal.size) return -1
        return nal[h].toInt() and 0x1F
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.video.H264NalParserTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/H264NalParser.kt app/src/test/java/com/yotam/droneedge/video/H264NalParserTest.kt
git commit -m "feat: H264NalParser (NAL type classification) + tests"
```

---

### Task 3: `H264PassthroughRecorder`

Integration code (MediaMuxer) — not unit-testable; verified on device in Task 6.

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/H264PassthroughRecorder.kt`

- [ ] **Step 1: Implement the recorder**

```kotlin
package com.droneedge.app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.droneedge.app.detection.Detection
import com.droneedge.app.video.H264NalParser
import com.droneedge.app.video.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the DJI stream by muxing the drone's already-encoded H.264 NAL units directly —
 * no decode/encode. Video arrives via [onEncodedSample] (teed from the source's NAL loop);
 * detections arrive via [onFrame] and are written to the JSON sidecar only.
 * Sample PTS and the JSON share the same `sessionStart` origin.
 */
class H264PassthroughRecorder : SessionRecorder {

    private data class Sample(val data: ByteArray, val tsMs: Long, val type: Int)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val samples = Channel<Sample>(Channel.UNLIMITED)
    private val jsonLock = Mutex()

    private var appContext: Context? = null
    private var sessionName = ""
    private var sessionStartMs = -1L
    private var declaredWidth = 1920
    private var declaredHeight = 1080

    private var muxer: MediaMuxer? = null
    private var videoFd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var jsonWriter: BufferedWriter? = null
    private var jsonUri: Uri? = null
    private var consumerJob: Job? = null

    // Consumer-coroutine-only state:
    private var trackIndex = -1
    private var started = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var frameCount = 0
    private var lastTsMs = 0L

    @Volatile private var stopped = false

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) =
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            declaredWidth = width
            declaredHeight = height
            sessionName = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            sessionStartMs = System.currentTimeMillis()
            stopped = false

            val vf = RecordingStorage.openVideoFile(appContext!!, sessionName)
            videoFd = vf.pfd
            videoUri = vf.uri
            muxer = MediaMuxer(vf.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val jf = RecordingStorage.openJsonWriter(appContext!!, sessionName)
            jsonWriter = jf.writer
            jsonUri = jf.uri
            jsonWriter?.appendLine("""{"sessionStart":$sessionStartMs}""")

            consumerJob = scope.launch { consume() }
            Unit
        }

    /** Called from the DJI decode loop (NOT suspend — must return immediately). */
    fun onEncodedSample(nal: ByteArray, nalType: Int) {
        if (stopped) return
        samples.trySend(Sample(nal, System.currentTimeMillis(), nalType))
    }

    private suspend fun consume() {
        for (s in samples) {
            when (s.type) {
                H264NalParser.NAL_SPS -> if (sps == null) sps = s.data
                H264NalParser.NAL_PPS -> if (pps == null) pps = s.data
            }
            if (!started) {
                val spsv = sps
                val ppsv = pps
                // Start the track only once we have parameter sets AND a keyframe to begin from.
                if (spsv != null && ppsv != null && s.type == H264NalParser.NAL_IDR) {
                    val mx = muxer ?: continue
                    val fmt = MediaFormat.createVideoFormat("video/avc", declaredWidth, declaredHeight).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(spsv))
                        setByteBuffer("csd-1", ByteBuffer.wrap(ppsv))
                    }
                    trackIndex = mx.addTrack(fmt)
                    mx.start()
                    started = true
                    writeSample(s)
                }
                // else: drop NALs before the first keyframe.
            } else if (s.type == 1 || s.type == H264NalParser.NAL_IDR) {
                // Write only VCL slices (non-IDR=1, IDR=5). SPS/PPS already in csd.
                writeSample(s)
            }
        }
    }

    private fun writeSample(s: Sample) {
        val mx = muxer ?: return
        val ptsUs = (s.tsMs - sessionStartMs) * 1000L
        val info = MediaCodec.BufferInfo().apply {
            set(0, s.data.size, ptsUs,
                if (s.type == H264NalParser.NAL_IDR) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        runCatching { mx.writeSampleData(trackIndex, ByteBuffer.wrap(s.data), info) }
        frameCount++
        lastTsMs = s.tsMs
    }

    override suspend fun onFrame(frame: VideoFrame, detections: List<Detection>) {
        if (stopped || detections.isEmpty()) return
        jsonLock.withLock {
            jsonWriter?.appendLine(
                DetectionEvent(frame.index, frame.timestampMs, detections).toJson()
            )
        }
    }

    override suspend fun stop(): RecordingResult = withContext(Dispatchers.IO) {
        stopped = true
        samples.close()
        consumerJob?.join()

        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { videoFd?.close() }
        videoFd = null
        jsonLock.withLock { runCatching { jsonWriter?.close() } }
        jsonWriter = null

        appContext?.let { ctx -> videoUri?.let { RecordingStorage.finalizeVideo(ctx, it) } }
        scope.cancel()

        val durationMs = if (started && lastTsMs > sessionStartMs) lastTsMs - sessionStartMs else 0L
        RecordingResult(
            videoUri = videoUri,
            jsonUri = jsonUri,
            sessionId = sessionName,
            frameCount = frameCount,
            durationMs = durationMs,
        )
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/H264PassthroughRecorder.kt
git commit -m "feat: H264PassthroughRecorder (mux raw DJI H.264 + JSON sidecar)"
```

---

### Task 4: DJI source `encodedSink` tee

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/video/DjiGogglesAccessorySource.kt`

- [ ] **Step 1: Add the sink property**

After the `frameIndex` field (around line 57), add:
```kotlin
    /**
     * Optional tap on complete H.264 NAL units (each beginning with a 00 00 01 start code),
     * called inline on the decode loop while recording. Null = no recording (default).
     * Second arg is the NAL type (see H264NalParser).
     */
    @Volatile var encodedSink: ((nal: ByteArray, nalType: Int) -> Unit)? = null
```

- [ ] **Step 2: Tee each complete NAL in the feed loop**

In the inner `while (ns < lastSc)` loop, right after the NAL boundary `ne` is computed and before/after feeding the decoder, add the tee. Replace this block:
```kotlin
                                    var ns = alignStart
                                    while (ns < lastSc) {
                                        val nx = nextStartCode(vbuf, ns + 3, lastSc)
                                        val ne = if (nx < 0) lastSc else nx
                                        val inputIdx = try { codec.dequeueInputBuffer(10_000) }
                                                       catch (e: IllegalStateException) { -1 }
```
with:
```kotlin
                                    var ns = alignStart
                                    while (ns < lastSc) {
                                        val nx = nextStartCode(vbuf, ns + 3, lastSc)
                                        val ne = if (nx < 0) lastSc else nx
                                        encodedSink?.let { sink ->
                                            val nal = vbuf.copyOfRange(ns, ne)
                                            sink(nal, H264NalParser.nalType(nal))
                                        }
                                        val inputIdx = try { codec.dequeueInputBuffer(10_000) }
                                                       catch (e: IllegalStateException) { -1 }
```

(The `codec != null` guard wraps this loop today. If `renderSurface`/`codec` can be null while recording, that's not a supported case — recording requires the live DJI session which always has the decoder. No change needed.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/DjiGogglesAccessorySource.kt
git commit -m "feat: DjiGogglesAccessorySource encodedSink tap on complete NALs"
```

---

### Task 5: Wire the passthrough recorder in `LiveViewModel`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Select the passthrough recorder for DJI and wire the sink in `armRecording`**

Replace the body of `armRecording()`:
```kotlin
    fun armRecording() {
        if (!canArm(_sessionState.value, _recordingState.value)) return
        val rec = recorderFactory()
        recorder = rec
        _recordingElapsedMs.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _recordingElapsedMs.value += 1000L
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
            _recordingState.value = RecordingState.ARMED
        }
    }
```
with:
```kotlin
    fun armRecording() {
        if (!canArm(_sessionState.value, _recordingState.value)) return
        // DJI streams record by muxing the raw drone H.264 (passthrough) — full-res, no re-encode.
        val src = videoSource
        val rec: SessionRecorder = if (src is com.droneedge.app.video.DjiGogglesAccessorySource) {
            com.droneedge.app.recording.H264PassthroughRecorder().also { passthrough ->
                src.encodedSink = { nal, type -> passthrough.onEncodedSample(nal, type) }
            }
        } else {
            recorderFactory()
        }
        recorder = rec
        _recordingElapsedMs.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _recordingElapsedMs.value += 1000L
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
            _recordingState.value = RecordingState.ARMED
        }
    }
```

- [ ] **Step 2: Unwire the sink in `disarmRecording`**

Replace the body of `disarmRecording()`:
```kotlin
    fun disarmRecording() {
        timerJob?.cancel()
        timerJob = null
        if (!canDisarm(_recordingState.value)) return
        val rec = recorder ?: return
        _recordingState.value = RecordingState.FINALIZING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            _pendingRename.value = result
            _recordingState.value = RecordingState.IDLE
            recorder = null
        }
    }
```
with (add the sink clear before stopping):
```kotlin
    fun disarmRecording() {
        timerJob?.cancel()
        timerJob = null
        if (!canDisarm(_recordingState.value)) return
        val rec = recorder ?: return
        // Stop teeing NALs before finalizing the muxer.
        (videoSource as? com.droneedge.app.video.DjiGogglesAccessorySource)?.encodedSink = null
        _recordingState.value = RecordingState.FINALIZING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            _pendingRename.value = result
            _recordingState.value = RecordingState.IDLE
            recorder = null
        }
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full test gate**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "feat: route DJI recording through H264PassthroughRecorder + wire NAL sink"
```

---

### Task 6: On-device verification (Tab, with goggles)

No code; the integration gate. Record results.

- [ ] **Step 1: Build + stage APK**

Run: `./gradlew assembleDebug`
Then copy to the drive if mounted: `cp app/build/outputs/apk/debug/app-debug.apk "/Volumes/NO NAME/DroneEdge-passthrough.apk"` (or install via adb to the Tab).

- [ ] **Step 2: Record a DJI session**

- Connect goggles, start the DJI stream, confirm live stream + inference look normal.
- Arm Record for ~10 s while the scene has motion, then Stop.

- [ ] **Step 3: Verify the recording**

- Open it in the Recordings screen (or pull it): plays back **full-resolution**, **smooth**, at **1× speed** (not slow-motion / not sped up), correct duration (~10 s).
- Confirm it isn't stuck pending: `adb shell "find /sdcard/Movies/DroneEdge -name '.pending*' -newermt '-3 minutes'"` → empty.
- Confirm the JSON exists with detections: `adb shell "cat /sdcard/Android/data/com.droneedge.app/files/recordings/<session>/detections.json | head"` → has `sessionStart` + detection lines.

- [ ] **Step 4: Verify the live path was unaffected**

- During recording, confirm inference fps stayed ~14 and latency stayed low (HUD).

- [ ] **Step 5: If playback is wrong (slow-mo / won't play / wrong duration)**

This is the known-risk area (PTS or SPS/PPS). Capture the symptom and check:
- Slow-mo/fast → PTS scaling (the `(tsMs - sessionStartMs) * 1000` math, or samples arriving faster/slower than wall-clock).
- Won't play → SPS/PPS csd or keyframe-start (verify `started` only triggers on SPS+PPS+IDR; verify samples include the start code).
Report findings; iterate on `H264PassthroughRecorder` (this is expected per the spec's risk note).

---

## Self-Review

- **Spec coverage:** passthrough mux via encodedSink (T3/T4), JSON via onFrame (T3), shared `sessionStart` timeline (T3 writes sessionStart + PTS relative to it), SPS/PPS→track + keyframe-start (T3 `consume`), DJI-only routing (T5), shared storage no-duplication (T1), on-device validation incl. slow-mo/duration (T6). Covered.
- **Placeholder scan:** none — every code step has full code.
- **Type consistency:** `encodedSink: (ByteArray, Int) -> Unit` defined in T4, called in T5, consumed by `onEncodedSample(nal, nalType)` in T3; `H264NalParser.nalType`/`NAL_*` defined T2, used T3/T4; `RecordingStorage.openVideoFile/openJsonWriter/finalizeVideo` defined T1, used T1+T3. Consistent.

## Notes / Risks
- Part 2 (GL calc) is a separate plan; this plan delivers full-res raw DJI recordings + JSON on its own.
- Muxing Annex-B (start-code) samples + SPS/PPS csd is the device-validated piece (T6). If a device rejects Annex-B samples, the fallback is to strip start codes and write length-prefixed (AVCC) samples — note it, don't pre-build it.
- Multi-slice frames (more than one VCL NAL per picture) would mux as separate samples; if the drone uses slicing and playback timing looks off, accumulate one access unit per PTS. Validate in T6 before adding complexity.
