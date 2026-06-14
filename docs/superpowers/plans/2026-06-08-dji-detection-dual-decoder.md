# DJI Detection — Dual Decoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable YOLO inference at ≥10fps on the DJI live stream by running a second H.264 decoder alongside the display decoder, capturing YUV frames into an ImageReader at a 10fps rate cap, converting to Bitmap, and injecting into VideoFrame.bitmap for the existing TfliteDetector pipeline.

**Architecture:** Tee the incoming H.264 Annex-B bytes into two `PipedOutputStream`s. The first (existing) feeds ExoPlayer → `renderSurface` for display. The second feeds an identical ExoPlayer → `ImageReader(YUV_420_888)`. An `OnImageAvailableListener` fires on a background `HandlerThread`, applies a 100ms timestamp gate (10fps cap), converts the YUV `Image` to an ARGB Bitmap via BT.601 integer math, and stores it in an `AtomicReference`. The USB read loop picks it up and attaches it to the emitted `VideoFrame`. `TfliteDetector` already handles `frame.bitmap == null` (returns empty list); non-inference frames are no-ops at near-zero cost.

**Tech Stack:** Kotlin, Media3 ExoPlayer (existing), `android.media.ImageReader` / `android.media.Image` (framework APIs, minSdk 28 ≥ required API 19), `AtomicReference`, `HandlerThread`. No new Gradle dependencies.

---

## File Map

| File | Change |
|---|---|
| `app/src/main/java/com/droneedge/app/recording/YuvConversion.kt` | Add `yuv420ToArgbPixels()` pure function |
| `app/src/test/java/com/droneedge/app/recording/YuvConversionTest.kt` | Add 3 tests for `yuv420ToArgbPixels` |
| `app/src/main/java/com/droneedge/app/video/DjiGogglesAccessorySource.kt` | Add inference pipe + ImageReader + second ExoPlayer + bitmap tee |

No changes to `TfliteDetector`, `LiveViewModel`, or `LiveScreen`.

---

## Task 1: Create feature branch

- [ ] **Step 1: Create and switch to feature branch**

```bash
git checkout main && git pull && git checkout -b feature/dji-detection-dual-decoder
```

Expected: prompt shows `feature/dji-detection-dual-decoder`

---

## Task 2: Add `yuv420ToArgbPixels` to YuvConversion.kt + tests

This is a pure function (no Android framework types in parameters) so it can be unit-tested on the JVM without Robolectric.

**Files:**
- Modify: `app/src/main/java/com/droneedge/app/recording/YuvConversion.kt`
- Modify: `app/src/test/java/com/droneedge/app/recording/YuvConversionTest.kt`

- [ ] **Step 1: Write the failing tests first**

Open `YuvConversionTest.kt` and append these three tests after the existing ones:

```kotlin
    @Test
    fun yuv420BlackFrameDecodesCorrectly() {
        // 2×2 all-black frame: Y=16, U=128, V=128 (BT.601 black level)
        // yRowStride=2, uvRowStride=1, uvPixelStride=1 (I420 planar)
        val yBytes = byteArrayOf(16, 16, 16, 16)
        val uBytes = byteArrayOf(128.toByte())
        val vBytes = byteArrayOf(128.toByte())
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        pixels.forEach { pixel ->
            assertEquals("alpha", 0xFF, (pixel ushr 24) and 0xFF)
            assertEquals("R", 0, (pixel shr 16) and 0xFF)
            assertEquals("G", 0, (pixel shr 8) and 0xFF)
            assertEquals("B", 0, pixel and 0xFF)
        }
    }

    @Test
    fun yuv420WhiteFrameDecodesCorrectly() {
        // 2×2 all-white frame: Y=235, U=128, V=128
        val yBytes = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte())
        val uBytes = byteArrayOf(128.toByte())
        val vBytes = byteArrayOf(128.toByte())
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        pixels.forEach { pixel ->
            assertEquals("alpha", 0xFF, (pixel ushr 24) and 0xFF)
            assertEquals("R", 255, (pixel shr 16) and 0xFF)
            assertEquals("G", 255, (pixel shr 8) and 0xFF)
            assertEquals("B", 255, pixel and 0xFF)
        }
    }

    @Test
    fun yuv420OutputSizeIsWidthTimesHeight() {
        val yBytes = ByteArray(4) { 16 }
        val uBytes = ByteArray(1) { 128.toByte() }
        val vBytes = ByteArray(1) { 128.toByte() }
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        assertEquals(4, pixels.size)
    }
```

- [ ] **Step 2: Run tests — expect FAIL (function not yet defined)**

```bash
./gradlew test --tests "com.droneedge.app.recording.YuvConversionTest" 2>&1 | tail -20
```

Expected: `error: unresolved reference: yuv420ToArgbPixels`

- [ ] **Step 3: Add `yuv420ToArgbPixels` to YuvConversion.kt**

Append at the bottom of `YuvConversion.kt` (after `nv12FromPixels`):

```kotlin
/**
 * Converts a YUV_420_888 image plane data into packed ARGB pixels (alpha=255).
 *
 * Handles both I420 planar (uvPixelStride=1) and NV12 semi-planar (uvPixelStride=2) layouts.
 * BT.601 limited-range coefficients.
 *
 * Parameters match android.media.Image.Plane values so the caller can extract bytes
 * and call this without an Android dependency in the function itself.
 */
fun yuv420ToArgbPixels(
    yBytes: ByteArray, yRowStride: Int,
    uBytes: ByteArray, vBytes: ByteArray,
    uvRowStride: Int, uvPixelStride: Int,
    width: Int, height: Int,
): IntArray {
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        for (col in 0 until width) {
            val y = (yBytes[row * yRowStride + col].toInt() and 0xFF) - 16
            val uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
            val u = (uBytes[uvIdx].toInt() and 0xFF) - 128
            val v = (vBytes[uvIdx].toInt() and 0xFF) - 128
            val r = ((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return pixels
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew test --tests "com.droneedge.app.recording.YuvConversionTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with 6 tests passing (3 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/droneedge/app/recording/YuvConversion.kt \
        app/src/test/java/com/droneedge/app/recording/YuvConversionTest.kt
git commit -m "$(cat <<'EOF'
feat: add yuv420ToArgbPixels for inference frame decode

Inverse of the existing RGB→YUV functions; converts YUV_420_888 image
plane data (I420 and NV12 layouts) to packed ARGB. Used by the second
H.264 decoder in DjiGogglesAccessorySource to produce Bitmaps for
TfliteDetector without GPU readback.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add inference decoder to DjiGogglesAccessorySource

**Files:**
- Modify: `app/src/main/java/com/droneedge/app/video/DjiGogglesAccessorySource.kt`

This task has no new unit-testable logic (it wires Android framework types together). Correctness is verified by the build gate and on-device testing.

- [ ] **Step 1: Add imports to DjiGogglesAccessorySource.kt**

After the existing import block, add:

```kotlin
import android.graphics.Bitmap
import android.media.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.droneedge.app.recording.yuv420ToArgbPixels
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
```

- [ ] **Step 2: Add inference state inside the `channelFlow` block, right after the existing pipe declarations**

Locate this block in `DjiGogglesAccessorySource.kt` (around line 79):
```kotlin
        val h264PipeOut = PipedOutputStream()
        val h264PipeIn  = PipedInputStream(h264PipeOut, 1_048_576) // 1 MB cap (~1.6s at 5Mbps)
```

Insert immediately after it:
```kotlin
        // --- Inference decoder: second pipe + ImageReader at 10fps cap ---
        val inferPipeOut = PipedOutputStream()
        val inferPipeIn  = PipedInputStream(inferPipeOut, 1_048_576)
        val inferImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 3)
        val pendingInferenceBitmap = AtomicReference<Bitmap?>(null)
        val lastInferenceMs = AtomicLong(0L)
        val inferHandlerThread = HandlerThread("inference-reader").also { it.start() }
        inferImageReader.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastInferenceMs.get() < 100L) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastInferenceMs.set(now)
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val w = image.width
                val h = image.height
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
                val pixels = yuv420ToArgbPixels(
                    yBytes, yPlane.rowStride,
                    uBytes, vBytes,
                    uPlane.rowStride, uPlane.pixelStride,
                    w, h,
                )
                val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                pendingInferenceBitmap.getAndSet(bmp)?.recycle()
            } finally {
                image.close()
            }
        }, Handler(inferHandlerThread.looper))
```

- [ ] **Step 3: Launch the inference ExoPlayer after the existing playerJob launch**

Locate `val playerJob = launch(Dispatchers.Main) {` and the closing `}` of that block. Insert this new job immediately after the closing brace:

```kotlin
        val inferPlayerJob = launch(Dispatchers.Main) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(200, 400, 50, 100)
                .build()
            val inferPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
            inferPlayer.setVideoSurface(inferImageReader.surface)
            val dsFactory: DataSource.Factory = DataSource.Factory { PipeDataSource(inferPipeIn) }
            val exFactory: ExtractorsFactory  = ExtractorsFactory { arrayOf(RawH264Extractor()) }
            val inferSource = ProgressiveMediaSource.Factory(dsFactory, exFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
            inferPlayer.setMediaSource(inferSource)
            inferPlayer.prepare()
            inferPlayer.play()
            log?.println("InferenceDecoder ExoPlayer started")
            try {
                awaitCancellation()
            } finally {
                inferPlayer.release()
                log?.println("InferenceDecoder ExoPlayer released")
            }
        }
```

- [ ] **Step 4: Tee VIDEO_IN bytes to the inference pipe**

Locate this block (around line 197):
```kotlin
                    if (port == VIDEO_IN && length > 0) {
                        h264PipeOut.write(data, i + HEADER_SIZE, length)
                        h264PipeOut.flush()
                        videoBytes += length
```

Change to:
```kotlin
                    if (port == VIDEO_IN && length > 0) {
                        h264PipeOut.write(data, i + HEADER_SIZE, length)
                        h264PipeOut.flush()
                        runCatching {
                            inferPipeOut.write(data, i + HEADER_SIZE, length)
                            inferPipeOut.flush()
                        }
                        videoBytes += length
```

- [ ] **Step 5: Attach pending inference bitmap to VideoFrame**

Locate the VideoFrame emission line:
```kotlin
                        send(VideoFrame(frameIndex++, System.currentTimeMillis(), width, height, null))
```

Change to:
```kotlin
                        val bmp = pendingInferenceBitmap.getAndSet(null)
                        send(VideoFrame(frameIndex++, System.currentTimeMillis(), width, height, bmp))
```

- [ ] **Step 6: Clean up inference resources in the finally block**

Locate the `finally {` block (around line 214). Add cleanup for inference resources at the top of the existing finally block, before the existing `playerJob.cancel()`:

```kotlin
        } finally {
            inferPlayerJob.cancel()
            inferHandlerThread.quitSafely()
            pendingInferenceBitmap.getAndSet(null)?.recycle()
            runCatching { inferPipeOut.close() }
            inferImageReader.close()
            playerJob.cancel()
            // ... rest of existing finally block unchanged
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/droneedge/app/video/DjiGogglesAccessorySource.kt
git commit -m "$(cat <<'EOF'
feat: add dual H.264 decoder for YOLO inference on DJI stream

Second ExoPlayer runs alongside the display decoder, teed from the same
USB H.264 pipe. Frames are captured via ImageReader (YUV_420_888) at a
100ms rate cap (10fps), converted to Bitmap via BT.601 yuv420ToArgbPixels,
and attached to VideoFrame.bitmap. TfliteDetector receives real frames;
null-bitmap frames (non-inference) remain instant no-ops as before.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Build gate and full test run

- [ ] **Step 1: Build debug APK**

```bash
./gradlew assembleDebug 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`

If there are compile errors, fix them before proceeding. Common issues:
- Wrong import for `ImageFormat` — must be `android.media.ImageFormat`, not `androidx.*`
- `inferPlayerJob` referenced before it's declared if the finally block order is wrong — declare `inferPlayerJob` as a `var` initialized to a no-op Job if needed

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` — all existing tests pass plus the 3 new `YuvConversionTest` tests.

- [ ] **Step 3: Commit any fixes if needed**

If you had to fix compile errors or test failures in steps 1–2, commit them now:
```bash
git add -p
git commit -m "$(cat <<'EOF'
fix: resolve compile errors in DJI dual-decoder wiring

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Open pull request

- [ ] **Step 1: Push branch**

```bash
git push -u origin feature/dji-detection-dual-decoder
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --title "feat: DJI dual-decoder YOLO inference at 10fps" --body "$(cat <<'EOF'
## Summary
- Adds a second ExoPlayer decoder in `DjiGogglesAccessorySource` that tees the H.264 USB stream into a `ImageReader(YUV_420_888)`
- Captures frames at a 100ms rate cap (≥10fps) on a background `HandlerThread`, converts YUV→Bitmap via BT.601 integer math, and attaches to `VideoFrame.bitmap`
- `TfliteDetector` runs as before — null-bitmap non-inference frames are immediate no-ops
- Adds `yuv420ToArgbPixels()` pure function to `YuvConversion.kt` with 3 unit tests
- No new Gradle dependencies; no changes to `LiveViewModel`, `TfliteDetector`, or `LiveScreen`

## Test plan
- [ ] `./gradlew assembleDebug` passes
- [ ] `./gradlew test` — all tests pass (6 YuvConversionTest, others unchanged)
- [ ] On Tab S10+ with DJI Goggles connected: start session with NORTH model active, confirm inference FPS HUD shows ≥10fps
- [ ] Confirm display stream is unaffected (preview FPS unchanged)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review checklist

**Spec coverage:**
- ✅ Dual decoder approach (second ExoPlayer + tee pipe)
- ✅ Rate-limited to 10fps via timestamp gate
- ✅ YUV_420_888 → Bitmap via BT.601 conversion
- ✅ No changes to LiveViewModel/TfliteDetector/LiveScreen
- ✅ No new Gradle dependencies
- ✅ Display decoder unaffected (inference errors wrapped in runCatching)
- ✅ Resources cleaned up on session end (inferPlayerJob.cancel, HandlerThread.quitSafely, ImageReader.close)

**Type consistency:**
- `yuv420ToArgbPixels` signature used in `YuvConversionTest.kt` matches what's defined in `YuvConversion.kt`
- `pendingInferenceBitmap` is `AtomicReference<Bitmap?>` — consistent across all usages
- `inferPlayerJob` is declared before the finally block references it

**Placeholder scan:** No TODOs, TBDs, or "similar to above" references.
