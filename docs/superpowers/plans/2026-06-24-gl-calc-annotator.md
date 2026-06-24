# GL "Calc" Annotator (Part 2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Calc boxes" action on a saved DJI recording that produces a new annotated MP4 by replaying `detections.json` onto the raw video via a GL transcode (HW decode → GPU composite boxes → HW encode), full-res, several× real-time, offline.

**Architecture:** `MediaExtractor` → `MediaCodec` decoder rendering to a `SurfaceTexture` (GL OES texture) → GLES2 composites the video frame + a per-frame box-overlay texture onto the **encoder's input Surface** → `MediaCodec` encoder → `MediaMuxer`. Boxes come from a pure `DetectionTrack` (parsed JSON, last-known at a frame's wall-clock). Runs as a coroutine with a progress `StateFlow` — no new dependency.

**Tech Stack:** Kotlin, `android.media.MediaExtractor/MediaCodec/MediaMuxer`, `android.opengl` (EGL14 + GLES20), `SurfaceTexture`, Coroutines/Flow, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-22-passthrough-recording-gl-calc-design.md`

**Package note:** dirs are `com/yotam/droneedge/...`, package decls are `com.droneedge.app.*` — match existing files. Build: `./gradlew :app:compileDebugKotlin`, test: `./gradlew :app:testDebugUnitTest`.

**Timeline contract (from Part 1):** the raw MP4's frame PTS start at 0; `detections.json` line 1 is `{"sessionStart":<wallMs>}` and each event has wall-clock `timestampMs`. A decoded frame at `framePtsUs` maps to `wallClockMs = sessionStart + framePtsUs/1000`. Boxes are normalized [0,1] → scale to frame W×H.

---

## File Structure

- **Create** `recording/calc/DetectionTrack.kt` — pure JSON→time-indexed boxes lookup.
- **Create** `recording/calc/OverlayRenderer.kt` — detections → transparent ARGB overlay Bitmap.
- **Create** `recording/calc/gl/EglCore.kt` — EGL14 context + window surface over the encoder input Surface.
- **Create** `recording/calc/gl/FrameCompositor.kt` — GLES2: draw OES video texture + 2D overlay texture.
- **Create** `recording/calc/VideoAnnotator.kt` — the decode→GL→encode→mux engine + progress callback.
- **Create** test `test/.../recording/calc/DetectionTrackTest.kt`.
- **Modify** `ui/recordings/RecordingsViewModel.kt` — `calc(entry)` coroutine + progress/state.
- **Modify** `ui/recordings/RecordingsScreen.kt` — "Calc boxes" button + progress UI.

---

### Task 1: `DetectionTrack` (pure JSON parse + lookup) — TDD

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/calc/DetectionTrack.kt`
- Test: `app/src/test/java/com/yotam/droneedge/recording/calc/DetectionTrackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.droneedge.app.recording.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackTest {

    private val lines = listOf(
        """{"sessionStart":1000}""",
        """{"frameIndex":0,"timestampMs":1100,"detections":[{"label":"person","confidence":0.9000,"left":0.1000,"top":0.2000,"right":0.3000,"bottom":0.4000}]}""",
        """{"frameIndex":5,"timestampMs":1300,"detections":[{"label":"car","confidence":0.8000,"left":0.5000,"top":0.5000,"right":0.6000,"bottom":0.7000}]}""",
    )

    @Test fun parsesSessionStart() {
        assertEquals(1000L, DetectionTrack.parse(lines).sessionStartMs)
    }

    @Test fun beforeFirstDetectionIsEmpty() {
        assertTrue(DetectionTrack.parse(lines).boxesAt(1050L).isEmpty())
    }

    @Test fun returnsLastKnownDetections() {
        val t = DetectionTrack.parse(lines)
        // between the two events -> the first (person)
        assertEquals("person", t.boxesAt(1200L).single().label)
        // at/after the second -> the car
        assertEquals("car", t.boxesAt(1300L).single().label)
        assertEquals("car", t.boxesAt(9999L).single().label)
    }

    @Test fun parsesBoundingBoxAndConfidence() {
        val d = DetectionTrack.parse(lines).boxesAt(1100L).single()
        assertEquals(0.9f, d.confidence, 1e-4f)
        assertEquals(0.1f, d.boundingBox.left, 1e-4f)
        assertEquals(0.4f, d.boundingBox.bottom, 1e-4f)
    }

    @Test fun emptyOrGarbageLinesAreIgnored() {
        val t = DetectionTrack.parse(listOf("", """{"sessionStart":0}""", "not json"))
        assertEquals(0L, t.sessionStartMs)
        assertTrue(t.boxesAt(100L).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.recording.calc.DetectionTrackTest"`
Expected: FAIL — `DetectionTrack` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.droneedge.app.recording.calc

import com.droneedge.app.detection.BoundingBox
import com.droneedge.app.detection.Detection

/**
 * Time-indexed view of a recorded `detections.json`. Pure Kotlin (regex parsing, JVM-testable).
 * [boxesAt] returns the most recent detections at or before a wall-clock time (last-known box,
 * matching the live overlay's behaviour between inferences).
 */
class DetectionTrack private constructor(
    val sessionStartMs: Long,
    private val times: LongArray,
    private val boxes: List<List<Detection>>,
) {
    /** Most recent detections at or before [wallClockMs]; empty if before the first event. */
    fun boxesAt(wallClockMs: Long): List<Detection> {
        if (times.isEmpty() || wallClockMs < times[0]) return emptyList()
        // largest index with times[i] <= wallClockMs (binary search)
        var lo = 0
        var hi = times.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= wallClockMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return boxes[ans]
    }

    companion object {
        private val SESSION_START = Regex(""""sessionStart"\s*:\s*(\d+)""")
        private val TIMESTAMP = Regex(""""timestampMs"\s*:\s*(\d+)""")
        private val DET = Regex(
            """\{"label":"([^"]*)","confidence":([-\d.]+),"left":([-\d.]+),"top":([-\d.]+),"right":([-\d.]+),"bottom":([-\d.]+)\}"""
        )

        fun parse(lines: List<String>): DetectionTrack {
            var sessionStart = 0L
            val ts = ArrayList<Long>()
            val bx = ArrayList<List<Detection>>()
            for (line in lines) {
                if (sessionStart == 0L) {
                    SESSION_START.find(line)?.let { sessionStart = it.groupValues[1].toLong() }
                }
                val tMatch = TIMESTAMP.find(line) ?: continue
                val t = tMatch.groupValues[1].toLong()
                val dets = DET.findAll(line).map { m ->
                    Detection(
                        label = m.groupValues[1],
                        confidence = m.groupValues[2].toFloat(),
                        boundingBox = BoundingBox(
                            left = m.groupValues[3].toFloat(),
                            top = m.groupValues[4].toFloat(),
                            right = m.groupValues[5].toFloat(),
                            bottom = m.groupValues[6].toFloat(),
                        ),
                    )
                }.toList()
                ts.add(t); bx.add(dets)
            }
            // events are already in chronological write order
            return DetectionTrack(sessionStart, ts.toLongArray(), bx)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.droneedge.app.recording.calc.DetectionTrackTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/calc/DetectionTrack.kt app/src/test/java/com/yotam/droneedge/recording/calc/DetectionTrackTest.kt
git commit -m "feat: DetectionTrack (parse detections.json, last-known boxes) + tests"
```

---

### Task 2: `OverlayRenderer` (detections → transparent overlay Bitmap)

Reuses the recorder's box-drawing style. On-device-verified (Canvas), but tiny and isolated.

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/calc/OverlayRenderer.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.droneedge.app.recording.calc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import com.droneedge.app.detection.Detection

/**
 * Renders bounding boxes + labels into a reusable transparent ARGB bitmap, matching the live
 * overlay / recorder style (FieldAccent orange). The bitmap is uploaded to a GL texture and
 * composited over the decoded video frame by [FrameCompositor].
 */
class OverlayRenderer(private val width: Int, private val height: Int) {

    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val boxPaint = Paint().apply {
        color = 0xFFF97316.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (3f * (height / 1080f)).coerceAtLeast(2f)
        isAntiAlias = false
    }
    private val labelBgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = (22f * (height / 1080f)).coerceAtLeast(12f)
        isAntiAlias = true
    }
    private val stripH = labelPaint.textSize * 1.5f

    /** Clears the bitmap and draws [detections]; returns the same bitmap instance. */
    fun render(detections: List<Detection>): Bitmap {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = width.toFloat()
        val h = height.toFloat()
        for (d in detections) {
            val l = d.boundingBox.left * w
            val t = d.boundingBox.top * h
            val r = d.boundingBox.right * w
            val b = d.boundingBox.bottom * h
            canvas.drawRect(l, t, r, b, boxPaint)
            val label = "${d.label} ${"%.0f".format(d.confidence * 100)}%"
            val lw = labelPaint.measureText(label)
            canvas.drawRect(l, t - stripH, l + lw + 8f, t, labelBgPaint)
            canvas.drawText(label, l + 4f, t - labelPaint.textSize * 0.35f, labelPaint)
        }
        return bitmap
    }

    fun release() = bitmap.recycle()
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/calc/OverlayRenderer.kt
git commit -m "feat: OverlayRenderer (boxes+labels into transparent overlay bitmap)"
```

---

### Task 3: GL core — `EglCore` + `FrameCompositor`

Integration (GLES2/EGL14). On-device-verified in Task 5. This is the hardest piece; expect on-device iteration on the OES transform/blend.

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/calc/gl/EglCore.kt`
- Create: `app/src/main/java/com/yotam/droneedge/recording/calc/gl/FrameCompositor.kt`

- [ ] **Step 1: Implement `EglCore.kt`** (EGL context + window surface on the encoder input Surface)

```kotlin
package com.droneedge.app.recording.calc.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Minimal EGL14 setup: an OpenGL ES 2 context with a window surface targeting [surface]. */
class EglCore(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    fun makeCurrent() =
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

    fun setPresentationTime(nsecs: Long) =
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
```

- [ ] **Step 2: Implement `FrameCompositor.kt`** (OES video texture + 2D overlay texture)

```kotlin
package com.droneedge.app.recording.calc.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the decoded video frame (OES external texture) full-screen, then blends a 2D RGBA
 * overlay texture (the boxes) on top. One [SurfaceTexture] feeds the OES texture.
 */
class FrameCompositor {

    val oesTextureId: Int
    val surfaceTexture: SurfaceTexture
    private val overlayTextureId: Int
    private val stMatrix = FloatArray(16)

    private val oesProgram: Int
    private val rgbaProgram: Int

    // Full-screen quad: pos(x,y) + tex(u,v). v flipped so the image is upright.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )); position(0)
        }

    init {
        oesProgram = buildProgram(VERT, FRAG_OES)
        rgbaProgram = buildProgram(VERT, FRAG_RGBA)
        oesTextureId = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        surfaceTexture = SurfaceTexture(oesTextureId)
    }

    /** Call after surfaceTexture.updateTexImage(); draws frame + overlay to the current EGL surface. */
    fun drawFrame(viewW: Int, viewH: Int, overlay: Bitmap) {
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1) video frame (OES)
        drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId, stMatrix, blend = false)

        // 2) overlay (RGBA, premultiplied alpha blend)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
        drawQuad(rgbaProgram, GLES20.GL_TEXTURE_2D, overlayTextureId, IDENTITY, blend = true)
    }

    private fun drawQuad(program: Int, target: Int, texId: Int, texMatrix: FloatArray, blend: Boolean) {
        GLES20.glUseProgram(program)
        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        surfaceTexture.release()
        GLES20.glDeleteTextures(2, intArrayOf(oesTextureId, overlayTextureId), 0)
        GLES20.glDeleteProgram(oesProgram)
        GLES20.glDeleteProgram(rgbaProgram)
    }

    private fun genTexture(target: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        const val VERT = """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }
        """
        const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
        const val FRAG_RGBA = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main() { vec4 c = texture2D(uTex, vTex); gl_FragColor = vec4(c.rgb * c.a, c.a); }
        """
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/calc/gl/EglCore.kt app/src/main/java/com/yotam/droneedge/recording/calc/gl/FrameCompositor.kt
git commit -m "feat: GL core (EglCore + FrameCompositor: OES frame + RGBA overlay)"
```

---

### Task 4: `VideoAnnotator` (decode → GL → encode → mux)

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/calc/VideoAnnotator.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.droneedge.app.recording.calc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import com.droneedge.app.recording.calc.gl.EglCore
import com.droneedge.app.recording.calc.gl.FrameCompositor
import java.nio.ByteBuffer

/**
 * Transcodes [inputFd]'s H.264 video into [outputFd], compositing detection boxes (from [track])
 * onto each frame via GL. Runs flat-out (no pacing). Reports progress in [0,1] via [onProgress].
 */
class VideoAnnotator(
    private val inputFd: ParcelFileDescriptor,
    private val outputFd: ParcelFileDescriptor,
    private val track: DetectionTrack,
    private val onProgress: (Float) -> Unit = {},
) {
    fun run() {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglCore? = null
        var compositor: FrameCompositor? = null
        val ht = HandlerThread("annot-st").also { it.start() }
        try {
            extractor.setDataSource(inputFd.fileDescriptor)
            val track0 = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            extractor.selectTrack(track0)
            val inFmt = extractor.getTrackFormat(track0)
            val w = inFmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L

            // Encoder (surface input) + EGL + compositor
            val outFmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            egl = EglCore(inputSurface)
            egl.makeCurrent()
            compositor = FrameCompositor()
            val overlay = OverlayRenderer(w, h)

            // Decoder rendering to the compositor's SurfaceTexture
            decoder = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFmt, android.view.Surface(compositor.surfaceTexture), null, 0)
            decoder.start()

            muxer = MediaMuxer(outputFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            var muxerStarted = false

            val frameReady = Object()
            var frameAvailable = false
            compositor.surfaceTexture.setOnFrameAvailableListener({
                synchronized(frameReady) { frameAvailable = true; frameReady.notifyAll() }
            }, Handler(ht.looper))

            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false

            fun drainEncoder(end: Boolean) {
                if (end) encoder!!.signalEndOfInputStream()
                while (true) {
                    val idx = encoder!!.dequeueOutputBuffer(bufInfo, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) break else continue }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer!!.addTrack(encoder!!.outputFormat); muxer!!.start(); muxerStarted = true
                    } else if (idx >= 0) {
                        val buf = encoder!!.getOutputBuffer(idx)!!
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufInfo.size = 0
                        if (bufInfo.size > 0 && muxerStarted) {
                            buf.position(bufInfo.offset); buf.limit(bufInfo.offset + bufInfo.size)
                            muxer!!.writeSampleData(muxTrack, buf, bufInfo)
                        }
                        encoder!!.releaseOutputBuffer(idx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            while (!decodeDone) {
                // feed decoder input
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(inIdx)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // drain decoder → render frame
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outIdx >= 0 -> {
                        val render = bufInfo.size > 0
                        val ptsUs = bufInfo.presentationTimeUs
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            // wait for the frame to land in the SurfaceTexture
                            synchronized(frameReady) {
                                while (!frameAvailable) frameReady.wait(2_000)
                                frameAvailable = false
                            }
                            compositor!!.surfaceTexture.updateTexImage()
                            val wallMs = track.sessionStartMs + ptsUs / 1000L
                            compositor.drawFrame(w, h, overlay.render(track.boxesAt(wallMs)))
                            egl!!.setPresentationTime(ptsUs * 1000L)
                            egl.swapBuffers()
                            drainEncoder(false)
                            if (durationUs > 0) onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            drainEncoder(true)
            overlay.release()
            onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { if (muxer != null) muxer.stop() }; runCatching { muxer?.release() }
            runCatching { compositor?.release() }
            runCatching { egl?.release() }
            runCatching { extractor.release() }
            ht.quitSafely()
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/calc/VideoAnnotator.kt
git commit -m "feat: VideoAnnotator (GL decode->composite boxes->encode->mux)"
```

---

### Task 5: Wire "Calc boxes" into Recordings (ViewModel + Screen)

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt`

- [ ] **Step 1: Add the calc job + progress state to `RecordingsViewModel`**

Add these members and function to the class body (keep existing members):

```kotlin
    // Calc progress: null = idle; 0f..1f = running for the named session.
    private val _calcSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val calcSession: kotlinx.coroutines.flow.StateFlow<String?> = _calcSession.asStateFlow()
    private val _calcProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val calcProgress: kotlinx.coroutines.flow.StateFlow<Float> = _calcProgress.asStateFlow()

    fun calc(entry: RecordingEntry) {
        if (_calcSession.value != null) return
        _calcSession.value = entry.sessionName
        _calcProgress.value = 0f
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = getApplication<android.app.Application>()
            val result = runCatching {
                com.droneedge.app.recording.calc.runCalc(ctx, entry) { p ->
                    _calcProgress.value = p
                }
            }
            result.exceptionOrNull()?.let { _error.value = "Calc failed: ${it.message}" }
            _calcSession.value = null
            reload()
        }
    }
```

(`_error` and `reload()` already exist in this ViewModel; `asStateFlow` is already imported.)

- [ ] **Step 2: Add the `runCalc` orchestration helper**

Create `app/src/main/java/com/yotam/droneedge/recording/calc/Calc.kt`:

```kotlin
package com.droneedge.app.recording.calc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.droneedge.app.ui.recordings.RecordingEntry
import java.io.File

/**
 * Reads the raw recording + its detections.json, runs [VideoAnnotator], and writes
 * `annotated_boxed.mp4` into the same MediaStore session folder.
 */
fun runCalc(context: Context, entry: RecordingEntry, onProgress: (Float) -> Unit) {
    val jsonFile = File(
        context.getExternalFilesDir(null),
        "recordings/${entry.sessionName}/detections.json",
    )
    require(jsonFile.exists()) { "no detections.json for ${entry.sessionName}" }
    val tdetect = DetectionTrack.parse(jsonFile.readLines())

    val inputFd = context.contentResolver.openFileDescriptor(entry.uri, "r")
        ?: error("cannot open ${entry.uri}")

    val outUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "annotated_boxed.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/${entry.sessionName}/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        context.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
        ) ?: error("MediaStore insert failed")
    } else {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MOVIES), "DroneEdge/${entry.sessionName}").also { it.mkdirs() }
        android.net.Uri.fromFile(File(dir, "annotated_boxed.mp4"))
    }
    val outputFd = context.contentResolver.openFileDescriptor(outUri, "rw")
        ?: error("cannot open output")

    try {
        VideoAnnotator(inputFd, outputFd, tdetect, onProgress).run()
    } finally {
        runCatching { inputFd.close() }
        runCatching { outputFd.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
            )
        }
    }
}
```

- [ ] **Step 3: Add the "Calc boxes" button + progress to `RecordingsScreen`**

In the per-entry UI (the row/card that already shows rename/delete actions — locate the action row by the existing rename/delete buttons), collect the state and add a button. Near the other `vm.<...>` state reads in the screen composable, add:
```kotlin
    val calcSession by vm.calcSession.collectAsStateWithLifecycle()
    val calcProgress by vm.calcProgress.collectAsStateWithLifecycle()
```
In the entry's action row, add:
```kotlin
        if (calcSession == entry.sessionName) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { calcProgress },
                modifier = Modifier.width(120.dp),
            )
        } else {
            androidx.compose.material3.TextButton(
                onClick = { vm.calc(entry) },
                enabled = calcSession == null,
            ) { androidx.compose.material3.Text(strings.calcBoxes) }
        }
```
Add the string `calcBoxes` to the strings holder used here (`AppStrings`) with value "Calc boxes" (mirror an existing entry like `dismiss`/`rename` — add to the same data class/object and any locale maps).

- [ ] **Step 4: Build + full gate**

Run: `./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL; unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/recordings/ app/src/main/java/com/yotam/droneedge/recording/calc/Calc.kt app/src/main/java/com/yotam/droneedge/ui/theme/
git commit -m "feat: Calc boxes action (RecordingsScreen button + progress + runCalc)"
```

---

### Task 6: On-device verification

No code; the integration gate. Record results.

- [ ] **Step 1: Build + install/stage**

Run: `./gradlew installDebug` (emulator) or copy `app/build/outputs/apk/debug/app-debug.apk` to the drive for the Tab.

- [ ] **Step 2: Run calc on a DJI recording**

- Open Recordings, pick a passthrough DJI recording that has detections, tap **Calc boxes**.
- Watch the progress bar advance; confirm it completes (no crash) faster than the clip's duration.

- [ ] **Step 3: Verify the output**

- A new `annotated_boxed.mp4` appears in the session. Play it: **full-resolution**, **boxes correctly placed and time-aligned** with the action, correct orientation, correct colours, plays at 1× with correct duration.
- Confirm not stuck pending: `adb shell "find /sdcard/Movies/DroneEdge -name '.pending*' -newermt '-5 minutes'"` → empty.

- [ ] **Step 4: If frames are upside-down / stretched / mis-blended**

Known GL-risk area (no real-time stall risk — just a wrong-looking frame):
- Upside-down → flip the quad's `v` coordinates (the OES `uTexMatrix` usually handles orientation; if double-flipped, use identity for the OES draw or remove the manual v-flip).
- Boxes wrong scale/position → confirm overlay drawn at full W×H and the overlay quad uses identity tex matrix.
- Overlay tinted/halo → blend mode (premultiplied: `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` with the shader's `rgb*a`).
Report the symptom and I'll adjust the compositor.

- [ ] **Step 5: Measure throughput**

Note calc time vs clip length (target several× real-time). Record in the spec's verification section if useful.

---

## Self-Review

- **Spec coverage:** raw+JSON input (T5 `runCalc`), DetectionTrack replay/last-known (T1), GL decode→composite→encode→mux (T3/T4), boxes+labels overlay (T2), `sessionStart`+PTS time mapping (T4 `wallMs = sessionStartMs + ptsUs/1000`), "Calc boxes" UI + background coroutine + progress, no new dependency (coroutine not WorkManager), output MediaStore registration (T5). Covered.
- **Placeholder scan:** all code steps have full code; no TBD. The RecordingsScreen edit (T5.3) references existing action-row + AppStrings by description because exact surrounding lines vary — the implementer locates the rename/delete row; this is a located edit, not a placeholder.
- **Type consistency:** `DetectionTrack.parse(List<String>)`/`boxesAt(Long)`/`sessionStartMs` (T1) used in T4/T5; `OverlayRenderer(w,h).render(List<Detection>): Bitmap` (T2) used in T4; `EglCore(Surface)` + `FrameCompositor()` API (T3) used in T4; `VideoAnnotator(inputFd, outputFd, track, onProgress).run()` (T4) used in T5; `runCalc(context, entry, onProgress)` (T5.2) used in T5.1. Consistent.

## Risks
- **GL correctness** (T3/T4): OES transform/orientation and overlay blend are the classic bugs — validated in T6 (fails as a wrong-looking frame, never a stall). Iterate there.
- **Time alignment drift:** mapping fixed-30fps PTS → wall-clock can drift on long clips vs the ~14fps detections; last-known boxes make small drift visually acceptable. If it matters, a follow-up is to record a per-frame pts→wallclock map in Part 1.
- **Decoder→SurfaceTexture frame sync:** the `frameAvailable` wait must precede `updateTexImage`; the 2s timeout guards against a missed callback.
