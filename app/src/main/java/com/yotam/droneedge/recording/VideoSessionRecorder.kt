package com.droneedge.app.recording

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
import android.os.ParcelFileDescriptor
import com.droneedge.app.detection.Detection
import com.droneedge.app.video.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
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
        color = 0xFFF97316.toInt()   // matches FieldAccent orange used by live overlay
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val labelBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
    }
    // Computed proportionally in start() so boxes look similar to the live overlay at playback scale
    private var labelStripH = 34f

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) =
        withContext(Dispatchers.IO) {
            appContext    = context.applicationContext
            sessionName   = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            encodedWidth  = ((width  + 15) / 16) * 16
            encodedHeight = ((height + 15) / 16) * 16
            encoderFps    = fps
            frameCount    = 0
            stopped       = false
            videoTrackIndex = -1
            muxerStarted  = false

            // Scale annotation sizes to frame height so they look similar
            // to the live overlay (Stroke 3f / 11sp) when the video is upscaled on playback.
            // Reference: 1080p → stroke 3px, text 22px; scale linearly to actual height.
            val drawScale = encodedHeight / 1080f
            boxPaint.strokeWidth = (3f * drawScale).coerceAtLeast(2f)
            labelPaint.textSize  = (22f * drawScale).coerceAtLeast(12f)
            labelStripH          = labelPaint.textSize * 1.5f

            firstTimestampMs = System.currentTimeMillis()
            val jf = RecordingStorage.openJsonWriter(context.applicationContext, sessionName)
            jsonWriter = jf.writer
            jsonRowUri = jf.uri
            jsonWriter?.appendLine("""{"sessionStart":$firstTimestampMs}""")

            val vf = RecordingStorage.openVideoFile(context.applicationContext, sessionName)
            videoFd = vf.pfd
            videoRowUri = vf.uri
            muxer = MediaMuxer(vf.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

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
                        canvas.drawRect(l, t - labelStripH, l + lw + 8f, t, labelBgPaint)
                        canvas.drawText(label, l + 4f, t - labelPaint.textSize * 0.35f, labelPaint)
                    }
                }

                // Scale to encoder dimensions if source size differs
                val scaled = if (annotated.width == encodedWidth && annotated.height == encodedHeight) {
                    annotated
                } else {
                    Bitmap.createScaledBitmap(annotated, encodedWidth, encodedHeight, true)
                }

                val inputIdx = enc.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val ptsUs = (frame.timestampMs - firstTimestampMs) * 1_000L
                    // Prefer getInputImage() — it exposes the encoder's actual plane strides and
                    // chroma layout (NV12 semi-planar on the Samsung/Adreno hardware AVC encoder),
                    // so rows land at the right offsets. Dumping tightly-packed I420 via
                    // getInputBuffer() ignores stride/pixelStride → green + wrong-colour patches.
                    // Fall back to the raw ByteBuffer path only if the image API is unavailable
                    // (software encoder).
                    // Capture the input buffer's full (strided) capacity first: this is the
                    // byte count the encoder must be told it received. getInputImage()
                    // invalidates this ByteBuffer, so read its capacity before filling the Image.
                    val inputCapacity = enc.getInputBuffer(inputIdx)?.capacity() ?: 0
                    val image = enc.getInputImage(inputIdx)
                    if (image != null) {
                        val nv12 = bitmapToNv12(scaled)
                        writeNv12ToImage(nv12, image, encodedWidth, encodedHeight)
                        // Pass the real frame size, NOT 0. A software encoder (e.g. the emulator's
                        // c2.android.avc.encoder) given size 0 reads no pixels, emits no output and
                        // then never produces an end-of-stream buffer, hanging stop() forever.
                        val size = if (inputCapacity > 0) inputCapacity else nv12.size
                        enc.queueInputBuffer(inputIdx, 0, size, ptsUs, 0)
                    } else {
                        val yuv = bitmapToI420(scaled)
                        val buf = enc.getInputBuffer(inputIdx)!!
                        buf.clear()
                        buf.put(yuv)
                        enc.queueInputBuffer(inputIdx, 0, yuv.size, ptsUs, 0)
                    }
                }
                if (scaled !== annotated) scaled.recycle()
                annotated.recycle()

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
                videoUri   = videoRowUri,
                jsonUri    = jsonRowUri,
                sessionId  = sessionName,
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
                    if (!muxerStarted) {
                        videoTrackIndex = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                    }
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

    private fun finalizeMediaStore() {
        val ctx = appContext ?: return
        videoRowUri?.let { RecordingStorage.finalizeVideo(ctx, it) }
    }
}
