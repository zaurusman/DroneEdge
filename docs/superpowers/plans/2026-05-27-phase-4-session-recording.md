# Phase 4: Session Recording and Metadata — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-triggered session recording that saves an annotated MP4 (bounding boxes burned in) and a newline-delimited JSON detection log to `Movies/DroneEdge/<session>/` on external storage.

**Architecture:** A new `SessionRecorder` interface mirrors the existing `VideoSource`/`Detector` pattern. `VideoSessionRecorder` implements it using MediaCodec H.264 ByteBuffer encoding and MediaMuxer MP4 muxing. `LiveViewModel` (refactored to `AndroidViewModel`) owns the recorder and exposes a `RecordingState` enum (IDLE/ARMED/FINALIZING) that drives a REC toggle button in `LiveScreen`.

**Tech Stack:** Kotlin coroutines, MediaCodec, MediaMuxer, MediaStore (API 29+), direct File I/O (API 28), Jetpack Compose, `kotlinx-coroutines-test` (already in deps).

---

## File Map

**New — main source set**

| Path | Responsibility |
|------|---------------|
| `recording/SessionRecorder.kt` | Interface + `RecordingResult` data class |
| `recording/DetectionEvent.kt` | JSON-serializable detection snapshot |
| `recording/YuvConversion.kt` | ARGB→NV12 conversion (pure Kotlin, Android-free logic) |
| `recording/VideoSessionRecorder.kt` | MediaCodec + MediaMuxer + JSON implementation |
| `ui/live/RecordingState.kt` | IDLE / ARMED / FINALIZING enum |

**New — test source set**

| Path | Responsibility |
|------|---------------|
| `recording/FakeSessionRecorder.kt` | Test double — records calls without doing I/O |
| `recording/YuvConversionTest.kt` | Unit tests for NV12 conversion |
| `recording/DetectionEventSerializationTest.kt` | Unit tests for JSON output |
| `recording/RecordingStateTransitionTest.kt` | Unit tests for pure guard functions |

**Modified**

| Path | Change |
|------|--------|
| `video/VideoSource.kt` | Add `val width: Int` and `val height: Int` |
| `video/FakeVideoSource.kt` | Implement new interface properties |
| `video/FileReplayVideoSource.kt` | Implement new interface properties |
| `ui/live/LiveViewModel.kt` | `AndroidViewModel`, recording state, `armRecording()`, `disarmRecording()` |
| `ui/live/LiveScreen.kt` | REC toggle button + completion snackbar |
| `AndroidManifest.xml` | `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` |

All source paths are relative to `app/src/main/java/com/yotam/droneedge/` (main) or `app/src/test/java/com/yotam/droneedge/` (test).

---

## Task 1: Create Feature Branch

**Files:** none

- [ ] **Step 1: Create branch and verify**

```bash
git checkout main && git pull
git checkout -b feature/phase-4-session-recording
git status
```

Expected: on branch `feature/phase-4-session-recording`, clean working tree.

---

## Task 2: Core Types — `RecordingState`, `SessionRecorder`, `DetectionEvent`

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/ui/live/RecordingState.kt`
- Create: `app/src/main/java/com/yotam/droneedge/recording/SessionRecorder.kt`
- Create: `app/src/main/java/com/yotam/droneedge/recording/DetectionEvent.kt`
- Create: `app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt`
- Create: `app/src/test/java/com/yotam/droneedge/recording/DetectionEventSerializationTest.kt`

- [ ] **Step 1: Write the failing serialization test**

Create `app/src/test/java/com/yotam/droneedge/recording/DetectionEventSerializationTest.kt`:

```kotlin
package com.yotam.droneedge.recording

import com.yotam.droneedge.detection.BoundingBox
import com.yotam.droneedge.detection.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEventSerializationTest {

    @Test
    fun singleDetectionContainsExpectedFields() {
        val event = DetectionEvent(
            frameIndex = 42L,
            timestampMs = 1000L,
            detections = listOf(
                Detection("drone", 0.9f, BoundingBox(0.1f, 0.2f, 0.5f, 0.6f))
            ),
        )
        val json = event.toJson()
        assertTrue(json.contains(""""frameIndex":42"""))
        assertTrue(json.contains(""""timestampMs":1000"""))
        assertTrue(json.contains(""""label":"drone""""))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertFalse(json.contains("\n"))
    }

    @Test
    fun emptyDetectionsProducesValidJson() {
        val json = DetectionEvent(0L, 0L, emptyList()).toJson()
        assertTrue(json.contains(""""detections":[]"""))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun multipleDetectionsSeparatedByCommas() {
        val event = DetectionEvent(
            frameIndex = 1L,
            timestampMs = 33L,
            detections = listOf(
                Detection("a", 0.8f, BoundingBox(0f, 0f, 0.5f, 0.5f)),
                Detection("b", 0.6f, BoundingBox(0.5f, 0.5f, 1f, 1f)),
            ),
        )
        val json = event.toJson()
        assertTrue(json.contains(""""label":"a""""))
        assertTrue(json.contains(""""label":"b""""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails (DetectionEvent doesn't exist yet)**

```bash
cd /Users/yotamtsabari/AndroidStudioProjects/DroneEdge
./gradlew :app:test --tests "com.yotam.droneedge.recording.DetectionEventSerializationTest" 2>&1 | tail -20
```

Expected: compile error — `DetectionEvent` not found.

- [ ] **Step 3: Create `RecordingState.kt`**

```kotlin
package com.yotam.droneedge.ui.live

enum class RecordingState {
    IDLE,
    ARMED,
    FINALIZING,
}
```

- [ ] **Step 4: Create `SessionRecorder.kt`**

```kotlin
package com.yotam.droneedge.recording

import android.content.Context
import android.net.Uri
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame

data class RecordingResult(
    val videoUri: Uri,
    val jsonUri: Uri,
    val frameCount: Int,
    val durationMs: Long,
)

interface SessionRecorder {
    suspend fun start(width: Int, height: Int, fps: Int, context: Context)
    suspend fun onFrame(frame: VideoFrame, detections: List<Detection>)
    suspend fun stop(): RecordingResult
}
```

- [ ] **Step 5: Create `DetectionEvent.kt`**

```kotlin
package com.yotam.droneedge.recording

import com.yotam.droneedge.detection.Detection

data class DetectionEvent(
    val frameIndex: Long,
    val timestampMs: Long,
    val detections: List<Detection>,
) {
    fun toJson(): String = buildString {
        append("""{"frameIndex":$frameIndex,"timestampMs":$timestampMs,"detections":[""")
        detections.forEachIndexed { i, d ->
            if (i > 0) append(",")
            append("""{"label":"${d.label}",""")
            append(""""confidence":${"%.4f".format(d.confidence)},""")
            append(""""left":${"%.4f".format(d.boundingBox.left)},""")
            append(""""top":${"%.4f".format(d.boundingBox.top)},""")
            append(""""right":${"%.4f".format(d.boundingBox.right)},""")
            append(""""bottom":${"%.4f".format(d.boundingBox.bottom)}}""")
        }
        append("]}")
    }
}
```

- [ ] **Step 6: Create `FakeSessionRecorder.kt` in test source set**

```kotlin
package com.yotam.droneedge.recording

import android.content.Context
import android.net.Uri
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame

class FakeSessionRecorder : SessionRecorder {
    var startCalled = false
    var stopCalled = false
    val framesReceived = mutableListOf<Pair<VideoFrame, List<Detection>>>()

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) {
        startCalled = true
    }

    override suspend fun onFrame(frame: VideoFrame, detections: List<Detection>) {
        framesReceived.add(frame to detections)
    }

    override suspend fun stop(): RecordingResult {
        stopCalled = true
        return RecordingResult(
            videoUri = Uri.EMPTY,
            jsonUri = Uri.EMPTY,
            frameCount = framesReceived.size,
            durationMs = 0L,
        )
    }
}
```

- [ ] **Step 7: Run the serialization test to verify it passes**

```bash
./gradlew :app:test --tests "com.yotam.droneedge.recording.DetectionEventSerializationTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 3 tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/RecordingState.kt \
        app/src/main/java/com/yotam/droneedge/recording/SessionRecorder.kt \
        app/src/main/java/com/yotam/droneedge/recording/DetectionEvent.kt \
        app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt \
        app/src/test/java/com/yotam/droneedge/recording/DetectionEventSerializationTest.kt
git commit -m "feat: add SessionRecorder interface, RecordingState, DetectionEvent types"
```

---

## Task 3: Extend `VideoSource` Interface with `width` and `height`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/video/VideoSource.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/video/FakeVideoSource.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/video/FileReplayVideoSource.kt`

- [ ] **Step 1: Add properties to `VideoSource` interface**

Replace the interface body in `video/VideoSource.kt` with:

```kotlin
package com.yotam.droneedge.video

import kotlinx.coroutines.flow.Flow

interface VideoSource {
    val frames: Flow<VideoFrame>
    val width: Int
    val height: Int
    fun start()
    fun stop()
}
```

- [ ] **Step 2: Implement in `FakeVideoSource`**

Add these two properties after the constructor parameters in `FakeVideoSource.kt`:

```kotlin
override val width: Int get() = frameWidthPx
override val height: Int get() = frameHeightPx
```

The full class now looks like (replace existing class body):

```kotlin
package com.yotam.droneedge.video

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeVideoSource(
    private val frameWidthPx: Int = 1280,
    private val frameHeightPx: Int = 720,
    private val targetFps: Int = 30,
) : VideoSource {

    override val width: Int get() = frameWidthPx
    override val height: Int get() = frameHeightPx

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val intervalMs = 1000L / targetFps
        while (running) {
            emit(
                VideoFrame(
                    index = frameIndex++,
                    timestampMs = System.currentTimeMillis(),
                    width = frameWidthPx,
                    height = frameHeightPx,
                )
            )
            delay(intervalMs)
        }
    }

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }
}
```

- [ ] **Step 3: Implement in `FileReplayVideoSource`**

Add these two properties to `FileReplayVideoSource`. The class already has `videoWidth` and `videoHeight` fields — add the interface properties right after the `init` block:

```kotlin
override val width: Int get() = videoWidth
override val height: Int get() = videoHeight
```

- [ ] **Step 4: Verify the build compiles**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/VideoSource.kt \
        app/src/main/java/com/yotam/droneedge/video/FakeVideoSource.kt \
        app/src/main/java/com/yotam/droneedge/video/FileReplayVideoSource.kt
git commit -m "feat: add width/height to VideoSource interface"
```

---

## Task 4: YUV Conversion Utility + Test

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/YuvConversion.kt`
- Create: `app/src/test/java/com/yotam/droneedge/recording/YuvConversionTest.kt`

- [ ] **Step 1: Write the failing YUV test**

Create `app/src/test/java/com/yotam/droneedge/recording/YuvConversionTest.kt`:

```kotlin
package com.yotam.droneedge.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvConversionTest {

    // BT.601 formulas:
    //   Y  = ( 66R + 129G +  25B + 128) >> 8 + 16
    //   Cb = (-38R -  74G + 112B + 128) >> 8 + 128
    //   Cr = (112R -  94G -  18B + 128) >> 8 + 128

    @Test
    fun blackPixelProducesCorrectYuv() {
        // 2×2 all-black ARGB_8888 pixels (0xFF000000)
        val black = 0xFF000000.toInt()
        val pixels = intArrayOf(black, black, black, black)
        val nv12 = nv12FromPixels(pixels, 2, 2)

        // Y = 16 for all 4 pixels
        assertEquals(16, nv12[0].toInt() and 0xFF)
        assertEquals(16, nv12[1].toInt() and 0xFF)
        assertEquals(16, nv12[2].toInt() and 0xFF)
        assertEquals(16, nv12[3].toInt() and 0xFF)
        // UV plane: Cb = 128, Cr = 128
        assertEquals(128, nv12[4].toInt() and 0xFF) // Cb
        assertEquals(128, nv12[5].toInt() and 0xFF) // Cr
    }

    @Test
    fun whitePixelProducesCorrectYuv() {
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(white, white, white, white)
        val nv12 = nv12FromPixels(pixels, 2, 2)

        // Y = 235 for white
        assertEquals(235, nv12[0].toInt() and 0xFF)
        // Cb = 128, Cr = 128 for achromatic
        assertEquals(128, nv12[4].toInt() and 0xFF)
        assertEquals(128, nv12[5].toInt() and 0xFF)
    }

    @Test
    fun outputSizeIsCorrect() {
        val pixels = IntArray(4 * 2) { 0xFF000000.toInt() }
        val nv12 = nv12FromPixels(pixels, 4, 2)
        // NV12 total size = width * height * 3 / 2
        assertEquals(4 * 2 * 3 / 2, nv12.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:test --tests "com.yotam.droneedge.recording.YuvConversionTest" 2>&1 | tail -20
```

Expected: compile error — `nv12FromPixels` not found.

- [ ] **Step 3: Create `YuvConversion.kt`**

```kotlin
package com.yotam.droneedge.recording

import android.graphics.Bitmap

fun bitmapToNv12(bitmap: Bitmap): ByteArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return nv12FromPixels(pixels, bitmap.width, bitmap.height)
}

fun nv12FromPixels(pixels: IntArray, width: Int, height: Int): ByteArray {
    val nv12 = ByteArray(width * height * 3 / 2)

    // Y plane — one byte per pixel
    for (i in pixels.indices) {
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8)  and 0xFF
        val b =  pixels[i]         and 0xFF
        nv12[i] = ((66 * r + 129 * g + 25 * b + 128) shr 8 + 16).toByte()
    }

    // UV plane — NV12: interleaved Cb then Cr, 2×2 subsampled
    val uvOffset = width * height
    var uvIndex = 0
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val p = pixels[row * 2 * width + col * 2]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            nv12[uvOffset + uvIndex++] = ((-38 * r - 74 * g + 112 * b + 128) shr 8 + 128).toByte()
            nv12[uvOffset + uvIndex++] = ((112 * r - 94 * g -  18 * b + 128) shr 8 + 128).toByte()
        }
    }
    return nv12
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:test --tests "com.yotam.droneedge.recording.YuvConversionTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/YuvConversion.kt \
        app/src/test/java/com/yotam/droneedge/recording/YuvConversionTest.kt
git commit -m "feat: add ARGB-to-NV12 conversion utility"
```

---

## Task 5: `VideoSessionRecorder` — Storage Setup

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt`

This task creates the class skeleton with `start()` (file creation) and the private storage helpers. `onFrame()` and `stop()` are stubbed.

- [ ] **Step 1: Create `VideoSessionRecorder.kt` with storage logic**

```kotlin
package com.yotam.droneedge.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoSessionRecorder : SessionRecorder {

    private val lock = Mutex()

    private var appContext: Context? = null
    private var sessionName: String = ""
    private var stopped = false

    // MediaCodec / MediaMuxer (added in Task 6)
    private var muxer: MediaMuxer? = null
    private var videoFd: ParcelFileDescriptor? = null

    private var jsonWriter: BufferedWriter? = null
    private var videoRowUri: Uri? = null
    private var jsonRowUri: Uri? = null

    private var encodedWidth = 0
    private var encodedHeight = 0
    private var encoderFps = 0
    private var frameCount = 0
    private var firstTimestampMs = -1L

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) =
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            sessionName = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            encodedWidth  = ((width  + 15) / 16) * 16
            encodedHeight = ((height + 15) / 16) * 16
            encoderFps    = fps
            frameCount    = 0
            firstTimestampMs = -1L
            stopped       = false

            val pfd = openVideoFile(context.applicationContext)
            videoFd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            jsonWriter = openJsonWriter(context.applicationContext)
        }

    override suspend fun onFrame(frame: VideoFrame, detections: List<Detection>) {
        // Encoding added in Task 6
    }

    override suspend fun stop(): RecordingResult = withContext(Dispatchers.IO) {
        lock.withLock {
            stopped = true
            muxer?.stop()
            muxer?.release()
            muxer = null
            videoFd?.close()
            videoFd = null
            jsonWriter?.close()
            jsonWriter = null
            finalizeMediaStore()
            val durationMs = if (encoderFps > 0 && frameCount > 0)
                (frameCount.toLong() * 1_000L) / encoderFps else 0L
            RecordingResult(
                videoUri   = videoRowUri ?: Uri.EMPTY,
                jsonUri    = jsonRowUri  ?: Uri.EMPTY,
                frameCount = frameCount,
                durationMs = durationMs,
            )
        }
    }

    // ── Storage helpers ───────────────────────────────────────────────────────

    private fun openVideoFile(context: Context): ParcelFileDescriptor {
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
            videoRowUri = uri
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Cannot open file descriptor for $uri")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "annotated.mp4")
            videoRowUri = Uri.fromFile(file)
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
        }
    }

    private fun openJsonWriter(context: Context): BufferedWriter {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "detections.json")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Movies/DroneEdge/$sessionName/")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
            ) ?: error("MediaStore insert failed for JSON")
            jsonRowUri = uri
            BufferedWriter(OutputStreamWriter(
                context.contentResolver.openOutputStream(uri)
                    ?: error("Cannot open output stream for $uri")
            ))
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "detections.json")
            jsonRowUri = Uri.fromFile(file)
            BufferedWriter(FileWriter(file))
        }
    }

    private fun finalizeMediaStore() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            videoRowUri?.let { uri ->
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
            }
            jsonRowUri?.let { uri ->
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                    null, null
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
git commit -m "feat: add VideoSessionRecorder with storage setup"
```

---

## Task 6: `VideoSessionRecorder` — MediaCodec Encoding

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt`

This task fills in `onFrame()` (bitmap annotation + NV12 encoding) and `stop()` (EOS + encoder teardown), and adds the private `drainEncoder()` and annotation helpers.

- [ ] **Step 1: Add imports and Paint fields**

At the top of `VideoSessionRecorder.kt`, add the following to the existing imports and class fields. Replace the entire file with the complete version below:

```kotlin
package com.yotam.droneedge.recording

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoSessionRecorder : SessionRecorder {

    private val lock = Mutex()

    private var appContext: Context? = null
    private var sessionName: String = ""
    private var stopped = false

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoFd: ParcelFileDescriptor? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false

    private var jsonWriter: BufferedWriter? = null
    private var videoRowUri: Uri? = null
    private var jsonRowUri: Uri? = null

    private var encodedWidth = 0
    private var encodedHeight = 0
    private var encoderFps = 0
    private var frameCount = 0
    private var firstTimestampMs = -1L

    private val bufferInfo = MediaCodec.BufferInfo()

    private val boxPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = false
    }
    private val labelBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
    }

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) =
        withContext(Dispatchers.IO) {
            appContext    = context.applicationContext
            sessionName   = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            encodedWidth  = ((width  + 15) / 16) * 16
            encodedHeight = ((height + 15) / 16) * 16
            encoderFps    = fps
            frameCount    = 0
            firstTimestampMs = -1L
            stopped       = false
            videoTrackIndex = -1
            muxerStarted  = false

            val pfd = openVideoFile(context.applicationContext)
            videoFd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            jsonWriter = openJsonWriter(context.applicationContext)

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, encodedWidth, encodedHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
        }

    override suspend fun onFrame(frame: VideoFrame, detections: List<Detection>) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (stopped) return@withLock
                val bmp = frame.bitmap ?: return@withLock
                val enc = encoder ?: return@withLock

                if (firstTimestampMs < 0L) firstTimestampMs = frame.timestampMs

                // Annotate a mutable copy
                val annotated = bmp.copy(Bitmap.Config.ARGB_8888, true)
                val cw = annotated.width.toFloat()
                val ch = annotated.height.toFloat()
                Canvas(annotated).also { canvas ->
                    detections.forEach { det ->
                        val l = det.boundingBox.left  * cw
                        val t = det.boundingBox.top   * ch
                        val r = det.boundingBox.right  * cw
                        val b = det.boundingBox.bottom * ch
                        canvas.drawRect(l, t, r, b, boxPaint)
                        val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
                        val lw = labelPaint.measureText(label)
                        canvas.drawRect(l, t - 34f, l + lw + 8f, t, labelBgPaint)
                        canvas.drawText(label, l + 4f, t - 8f, labelPaint)
                    }
                }

                // Scale to encoder dimensions if source size differs
                val scaled = if (annotated.width == encodedWidth && annotated.height == encodedHeight) {
                    annotated
                } else {
                    Bitmap.createScaledBitmap(annotated, encodedWidth, encodedHeight, true)
                }

                val yuv = bitmapToNv12(scaled)
                if (scaled !== annotated) scaled.recycle()
                annotated.recycle()

                // Feed NV12 bytes to encoder
                val inputIdx = enc.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val buf = enc.getInputBuffer(inputIdx)!!
                    buf.clear()
                    buf.put(yuv)
                    val ptsUs = (frame.timestampMs - firstTimestampMs) * 1_000L
                    enc.queueInputBuffer(inputIdx, 0, yuv.size, ptsUs, 0)
                }

                drainEncoder(endOfStream = false)

                if (detections.isNotEmpty()) {
                    jsonWriter?.appendLine(
                        DetectionEvent(frame.index, frame.timestampMs, detections).toJson()
                    )
                }

                frameCount++
            }
        }
    }

    override suspend fun stop(): RecordingResult = withContext(Dispatchers.IO) {
        lock.withLock {
            stopped = true
            val enc = encoder
            if (enc != null) {
                val inputIdx = enc.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    enc.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(endOfStream = true)
                enc.stop()
                enc.release()
                encoder = null
            }
            muxer?.stop()
            muxer?.release()
            muxer = null
            videoFd?.close()
            videoFd = null
            jsonWriter?.close()
            jsonWriter = null
            finalizeMediaStore()

            val durationMs = if (encoderFps > 0 && frameCount > 0)
                (frameCount.toLong() * 1_000L) / encoderFps else 0L
            RecordingResult(
                videoUri   = videoRowUri ?: Uri.EMPTY,
                jsonUri    = jsonRowUri  ?: Uri.EMPTY,
                frameCount = frameCount,
                durationMs = durationMs,
            )
        }
    }

    // ── Encoder drain ─────────────────────────────────────────────────────────

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mx  = muxer   ?: return
        val timeoutUs = if (endOfStream) 100_000L else 0L
        while (true) {
            val outputIdx = enc.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = mx.addTrack(enc.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
                outputIdx >= 0 -> {
                    val buf = enc.getOutputBuffer(outputIdx)
                    if (buf != null
                        && muxerStarted
                        && bufferInfo.size > 0
                        && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        mx.writeSampleData(videoTrackIndex, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outputIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    // ── Storage helpers ───────────────────────────────────────────────────────

    private fun openVideoFile(context: Context): ParcelFileDescriptor {
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
            videoRowUri = uri
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Cannot open file descriptor for $uri")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "annotated.mp4")
            videoRowUri = Uri.fromFile(file)
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
        }
    }

    private fun openJsonWriter(context: Context): BufferedWriter {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "detections.json")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Movies/DroneEdge/$sessionName/")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
            ) ?: error("MediaStore insert failed for JSON")
            jsonRowUri = uri
            BufferedWriter(OutputStreamWriter(
                context.contentResolver.openOutputStream(uri)
                    ?: error("Cannot open output stream for $uri")
            ))
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "detections.json")
            jsonRowUri = Uri.fromFile(file)
            BufferedWriter(FileWriter(file))
        }
    }

    private fun finalizeMediaStore() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            videoRowUri?.let { uri ->
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
            }
            jsonRowUri?.let { uri ->
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                    null, null
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
git commit -m "feat: add MediaCodec H.264 encoding in VideoSessionRecorder"
```

---

## Task 7: LiveViewModel Changes + Guard Function Tests

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`
- Create: `app/src/test/java/com/yotam/droneedge/recording/RecordingStateTransitionTest.kt`

- [ ] **Step 1: Write the failing guard tests**

Create `app/src/test/java/com/yotam/droneedge/recording/RecordingStateTransitionTest.kt`:

```kotlin
package com.yotam.droneedge.recording

import com.yotam.droneedge.ui.live.RecordingState
import com.yotam.droneedge.ui.live.SessionState
import com.yotam.droneedge.ui.live.canArm
import com.yotam.droneedge.ui.live.canDisarm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTransitionTest {

    @Test
    fun canArmTrueOnlyWhenRunningAndIdle() {
        assertTrue(canArm(SessionState.RUNNING, RecordingState.IDLE))
        assertFalse(canArm(SessionState.IDLE,     RecordingState.IDLE))
        assertFalse(canArm(SessionState.STOPPING, RecordingState.IDLE))
        assertFalse(canArm(SessionState.RUNNING,  RecordingState.ARMED))
        assertFalse(canArm(SessionState.RUNNING,  RecordingState.FINALIZING))
    }

    @Test
    fun canDisarmTrueOnlyWhenArmed() {
        assertTrue(canDisarm(RecordingState.ARMED))
        assertFalse(canDisarm(RecordingState.IDLE))
        assertFalse(canDisarm(RecordingState.FINALIZING))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:test --tests "com.yotam.droneedge.recording.RecordingStateTransitionTest" 2>&1 | tail -20
```

Expected: compile error — `canArm`/`canDisarm` not found.

- [ ] **Step 3: Replace `LiveViewModel.kt` with the updated version**

```kotlin
package com.yotam.droneedge.ui.live

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.recording.RecordingResult
import com.yotam.droneedge.recording.SessionRecorder
import com.yotam.droneedge.recording.VideoSessionRecorder
import com.yotam.droneedge.detection.Detector
import com.yotam.droneedge.detection.FakeDetector
import com.yotam.droneedge.detection.TfliteDetector
import com.yotam.droneedge.video.FakeVideoSource
import com.yotam.droneedge.video.FileReplayVideoSource
import com.yotam.droneedge.video.VideoFrame
import com.yotam.droneedge.video.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun canArm(sessionState: SessionState, recordingState: RecordingState): Boolean =
    sessionState == SessionState.RUNNING && recordingState == RecordingState.IDLE

internal fun canDisarm(recordingState: RecordingState): Boolean =
    recordingState == RecordingState.ARMED

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    // ── Pipeline (swappable while IDLE) ──────────────────────────────────────
    private var videoSource: VideoSource = FakeVideoSource()
    private var detector: Detector = FakeDetector()
    private var tfliteDetector: TfliteDetector? = null

    // ── Recorder (injectable for tests) ──────────────────────────────────────
    internal var recorderFactory: () -> SessionRecorder = { VideoSessionRecorder() }
    private var recorder: SessionRecorder? = null

    // ── Video URI (null = fake source) ────────────────────────────────────────
    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    // ── Detector mode ─────────────────────────────────────────────────────────
    private val _detectorMode = MutableStateFlow(DetectorMode.FAKE)
    val detectorMode: StateFlow<DetectorMode> = _detectorMode.asStateFlow()

    // ── Session state ─────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // ── Recording state ───────────────────────────────────────────────────────
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    // ── Last completed recording ──────────────────────────────────────────────
    private val _lastRecording = MutableStateFlow<RecordingResult?>(null)
    val lastRecording: StateFlow<RecordingResult?> = _lastRecording.asStateFlow()

    // ── Detection results ─────────────────────────────────────────────────────
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    // ── Error message ─────────────────────────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── FPS ───────────────────────────────────────────────────────────────────
    private val _previewFps = MutableStateFlow(0f)
    val previewFps: StateFlow<Float> = _previewFps.asStateFlow()

    private val _inferenceFps = MutableStateFlow(0f)
    val inferenceFps: StateFlow<Float> = _inferenceFps.asStateFlow()

    private var lastPreviewFrameMs = 0L
    private var lastInferenceMs = 0L
    private var pipelineJob: Job? = null

    // ── Source selection ──────────────────────────────────────────────────────

    fun useFileSource(uri: Uri, context: android.content.Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FileReplayVideoSource(uri, context.applicationContext)
        _videoUri.value = uri
    }

    fun useFakeSource() {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FakeVideoSource()
        _videoUri.value = null
    }

    // ── Detector selection ────────────────────────────────────────────────────

    fun setDetectorMode(mode: DetectorMode, context: android.content.Context? = null) {
        if (_sessionState.value != SessionState.IDLE) return
        when (mode) {
            DetectorMode.FAKE -> {
                tfliteDetector?.close()
                tfliteDetector = null
                detector = FakeDetector()
                _detectorMode.value = DetectorMode.FAKE
                _error.value = null
            }
            DetectorMode.TFLITE -> {
                if (context == null) return
                try {
                    val tfd = TfliteDetector(context.applicationContext)
                    tfliteDetector?.close()
                    tfliteDetector = tfd
                    detector = tfd
                    _detectorMode.value = DetectorMode.TFLITE
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = "TFLite load failed: ${e.message}"
                }
            }
        }
    }

    fun clearError() { _error.value = null }

    // ── Recording control ─────────────────────────────────────────────────────

    fun armRecording() {
        if (!canArm(_sessionState.value, _recordingState.value)) return
        val rec = recorderFactory()
        recorder = rec
        viewModelScope.launch(Dispatchers.IO) {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
            _recordingState.value = RecordingState.ARMED
        }
    }

    fun disarmRecording() {
        if (!canDisarm(_recordingState.value)) return
        val rec = recorder ?: return
        _recordingState.value = RecordingState.FINALIZING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            _lastRecording.value = result
            _recordingState.value = RecordingState.IDLE
            recorder = null
        }
    }

    fun clearLastRecording() { _lastRecording.value = null }

    // ── Session control ───────────────────────────────────────────────────────

    fun start() {
        if (_sessionState.value != SessionState.IDLE) return
        _sessionState.value = SessionState.RUNNING
        lastPreviewFrameMs = 0L
        lastInferenceMs = 0L
        videoSource.start()

        pipelineJob = viewModelScope.launch {
            val latestFrame = MutableStateFlow<VideoFrame?>(null)

            launch {
                videoSource.frames.collect { frame ->
                    val now = System.currentTimeMillis()
                    if (lastPreviewFrameMs > 0L) {
                        val dt = now - lastPreviewFrameMs
                        if (dt > 0) _previewFps.value =
                            _previewFps.value * 0.85f + (1000f / dt) * 0.15f
                    }
                    lastPreviewFrameMs = now
                    latestFrame.value = frame
                }
            }

            launch(Dispatchers.Default) {
                latestFrame.filterNotNull().collect { frame ->
                    val results = detector.detect(frame)
                    _detections.value = results

                    if (_recordingState.value == RecordingState.ARMED) {
                        recorder?.onFrame(frame, results)
                    }

                    val now = System.currentTimeMillis()
                    if (lastInferenceMs > 0L) {
                        val dt = now - lastInferenceMs
                        if (dt > 0) _inferenceFps.value =
                            _inferenceFps.value * 0.85f + (1000f / dt) * 0.15f
                    }
                    lastInferenceMs = now
                }
            }
        }
    }

    fun stop() {
        if (_sessionState.value != SessionState.RUNNING) return
        if (canDisarm(_recordingState.value)) disarmRecording()
        _sessionState.value = SessionState.STOPPING
        videoSource.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        _detections.value = emptyList()
        _previewFps.value = 0f
        _inferenceFps.value = 0f
        _sessionState.value = SessionState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
        if (canDisarm(_recordingState.value)) disarmRecording()
        videoSource.stop()
        pipelineJob?.cancel()
        tfliteDetector?.close()
    }
}
```

- [ ] **Step 4: Run the guard tests to verify they pass**

```bash
./gradlew :app:test --tests "com.yotam.droneedge.recording.RecordingStateTransitionTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Run all unit tests**

```bash
./gradlew :app:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt \
        app/src/test/java/com/yotam/droneedge/recording/RecordingStateTransitionTest.kt
git commit -m "feat: add recording state machine to LiveViewModel"
```

---

## Task 8: LiveScreen UI — REC Button + Snackbar

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Update `LiveScreen.kt`**

Make the following changes to `LiveScreen.kt`:

**1. Add new state collection** — add these two lines alongside the other `collectAsStateWithLifecycle()` calls at the top of the `LiveScreen` composable:

```kotlin
val recordingState by vm.recordingState.collectAsStateWithLifecycle()
val lastRecording  by vm.lastRecording.collectAsStateWithLifecycle()
```

**2. Add the REC button** — in the bottom controls row, add the REC button BEFORE the Start/Stop button. Replace the `Row` that contains the Start/Stop button with:

```kotlin
Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment     = Alignment.CenterVertically,
) {
    // File picker / clear button (only while idle)
    if (sessionState == SessionState.IDLE) {
        if (videoUri == null) {
            OutlinedButton(onClick = { filePicker.launch("video/*") }) {
                Text("Pick Video")
            }
        } else {
            OutlinedButton(
                onClick = { vm.useFakeSource() },
                colors  = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Text("Clear Video")
            }
        }
    }

    // REC toggle (only while running)
    if (sessionState == SessionState.RUNNING) {
        when (recordingState) {
            RecordingState.IDLE -> OutlinedButton(
                onClick = { vm.armRecording() },
            ) { Text("REC") }

            RecordingState.ARMED -> Button(
                onClick = { vm.disarmRecording() },
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor   = Color.White,
                ),
            ) { Text("● REC") }

            RecordingState.FINALIZING -> OutlinedButton(
                onClick  = {},
                enabled  = false,
            ) { Text("Saving…") }
        }
    }

    // Start / Stop
    Button(
        onClick = {
            when (sessionState) {
                SessionState.IDLE     -> vm.start()
                SessionState.RUNNING  -> vm.stop()
                SessionState.STOPPING -> Unit
            }
        },
        enabled = sessionState != SessionState.STOPPING,
    ) {
        Text(
            text = when (sessionState) {
                SessionState.IDLE     -> "Start"
                SessionState.RUNNING  -> "Stop"
                SessionState.STOPPING -> "Stopping…"
            }
        )
    }
}
```

**3. Add the completion snackbar** — add this block AFTER the existing error snackbar block (still inside the outer `Box`):

```kotlin
if (lastRecording != null) {
    Snackbar(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
        action = {
            TextButton(onClick = { vm.clearLastRecording() }) { Text("Dismiss") }
        },
    ) {
        Text("Saved to Movies/DroneEdge/")
    }
}
```

**4. Add the missing imports** — ensure these are in the import block:

```kotlin
import com.yotam.droneedge.ui.live.RecordingState
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: add REC toggle button and recording completion snackbar"
```

---

## Task 9: Manifest Permission

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add `WRITE_EXTERNAL_STORAGE` for API 28 only**

Add the following line inside `<manifest>`, BEFORE the `<application>` tag:

```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

The full manifest:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools" >

    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.DroneEdge" >
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.DroneEdge" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add WRITE_EXTERNAL_STORAGE permission for API 28"
```

---

## Task 10: Build Gate + PR

**Files:** none

- [ ] **Step 1: Full build + all tests**

```bash
./gradlew assembleDebug test 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`. All unit tests pass (YuvConversionTest × 3, DetectionEventSerializationTest × 3, RecordingStateTransitionTest × 2 + all pre-existing tests).

- [ ] **Step 2: Fix any errors before continuing**

If there are compile errors or test failures, fix them now. Do not proceed to Step 3 with a broken build.

- [ ] **Step 3: Open PR**

```bash
gh pr create \
  --base main \
  --title "feat: phase 4 — session recording and metadata" \
  --body "$(cat <<'EOF'
## Summary

- Adds user-triggered annotated MP4 recording with bounding boxes burned in
- Writes newline-delimited JSON detection log alongside the video
- Files saved to `Movies/DroneEdge/session_<timestamp>/` on external storage
- REC toggle button appears while session is running; completion snackbar confirms save location
- `SessionRecorder` interface mirrors existing `VideoSource`/`Detector` pattern for future replaceability
- `LiveViewModel` refactored to `AndroidViewModel`; `VideoSource` interface extended with `width`/`height`

## Test plan

- [ ] Run `./gradlew test` — all 8 unit tests pass
- [ ] Run `./gradlew assembleDebug` — clean build
- [ ] On emulator/device: Start session → tap REC → let run 5 s → tap REC again → confirm "Saved to Movies/DroneEdge/" snackbar
- [ ] Verify `Movies/DroneEdge/session_.../annotated.mp4` exists and plays with visible bounding boxes
- [ ] Verify `Movies/DroneEdge/session_.../detections.json` contains newline-delimited JSON entries
- [ ] Confirm Stop while ARMED also finalizes recording cleanly

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Verify PR was created and report branch + commit hash**

```bash
git log --oneline -8
gh pr view --web
```
