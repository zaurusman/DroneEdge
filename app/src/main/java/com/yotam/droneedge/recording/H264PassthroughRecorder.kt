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
import kotlinx.coroutines.CoroutineExceptionHandler
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
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
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

    private class Sample(val data: ByteArray, val tsMs: Long, val type: Int)

    // A recording fault must NEVER crash the app / kill the live stream. This handler is the
    // last-resort net for the consumer coroutine; consume() also try/catches internally.
    private var log: PrintWriter? = null
    private val errHandler = CoroutineExceptionHandler { _, e ->
        log?.println("FATAL recorder coroutine: ${e.stackTraceToString()}"); log?.flush()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + errHandler)
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
            log = runCatching {
                val dir = appContext!!.getExternalFilesDir("logs").also { it?.mkdirs() }
                PrintWriter(FileWriter(File(dir, "passthrough_log.txt"), false), true)
            }.getOrNull()
            log?.println("=== passthrough start ${declaredWidth}x${declaredHeight} @ $sessionStartMs ===")

            try {
                val vf = RecordingStorage.openVideoFile(appContext!!, sessionName)
                videoFd = vf.pfd
                videoUri = vf.uri
                muxer = MediaMuxer(vf.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val jf = RecordingStorage.openJsonWriter(appContext!!, sessionName)
                jsonWriter = jf.writer
                jsonUri = jf.uri
                jsonWriter?.appendLine("""{"sessionStart":$sessionStartMs}""")

                consumerJob = scope.launch { consume() }
            } catch (e: Throwable) {
                log?.println("start() failed: ${e.stackTraceToString()}")
                runCatching { muxer?.release() }
                muxer = null
                runCatching { jsonWriter?.close() }
                jsonWriter = null
                runCatching { videoFd?.close() }
                videoFd = null
                // Remove the orphaned pending MediaStore row so it doesn't linger.
                videoUri?.let { u -> runCatching { appContext?.contentResolver?.delete(u, null, null) } }
                videoUri = null
                scope.cancel()
                throw e
            }
            Unit
        }

    /** Called from the DJI decode loop (NOT suspend — must return immediately). */
    fun onEncodedSample(nal: ByteArray, nalType: Int) {
        if (stopped) return
        samples.trySend(Sample(nal, System.currentTimeMillis(), nalType))
    }

    private suspend fun consume() {
        try {
            for (s in samples) {
                when (s.type) {
                    H264NalParser.NAL_SPS -> if (sps == null) { sps = s.data; log?.println("SPS ${s.data.size}B") }
                    H264NalParser.NAL_PPS -> if (pps == null) { pps = s.data; log?.println("PPS ${s.data.size}B") }
                }
                if (!started) {
                    val spsv = sps
                    val ppsv = pps
                    // Start the track only once we have parameter sets AND a keyframe to begin from.
                    if (spsv != null && ppsv != null && s.type == H264NalParser.NAL_IDR) {
                        val mx = muxer ?: continue
                        log?.println("starting muxer ${declaredWidth}x${declaredHeight} csd0=${spsv.size} csd1=${ppsv.size} firstIdr=${s.data.size}B")
                        val fmt = MediaFormat.createVideoFormat("video/avc", declaredWidth, declaredHeight).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(spsv))
                            setByteBuffer("csd-1", ByteBuffer.wrap(ppsv))
                        }
                        trackIndex = mx.addTrack(fmt)
                        log?.println("addTrack -> $trackIndex; calling start()")
                        mx.start()
                        started = true
                        log?.println("muxer started; writing first sample")
                        writeSample(s)
                        log?.println("first sample written (frameCount=$frameCount)")
                    }
                    // else: drop NALs before the first keyframe.
                } else if (s.type == 1 || s.type == H264NalParser.NAL_IDR) {
                    // Write only VCL slices (non-IDR=1, IDR=5). SPS/PPS already in csd.
                    writeSample(s)
                }
            }
            log?.println("consumer ended normally (frameCount=$frameCount)")
        } catch (e: Throwable) {
            // Do NOT rethrow: a recording failure must not crash the app or kill the live stream.
            log?.println("EXCEPTION in consume (frameCount=$frameCount): ${e.stackTraceToString()}")
            log?.flush()
            stopped = true
        }
    }

    private fun writeSample(s: Sample) {
        val mx = muxer ?: return
        val ptsUs = (s.tsMs - sessionStartMs) * 1000L
        val info = MediaCodec.BufferInfo().apply {
            set(0, s.data.size, ptsUs,
                if (s.type == H264NalParser.NAL_IDR) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        val r = runCatching { mx.writeSampleData(trackIndex, ByteBuffer.wrap(s.data), info) }
        if (r.isSuccess) {
            frameCount++
            lastTsMs = s.tsMs
        } else {
            log?.println("writeSampleData failed @frame$frameCount: ${r.exceptionOrNull()?.stackTraceToString()}")
        }
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
        log?.println("stop() requested (started=$started frameCount=$frameCount)")
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
        log?.println("stopped (started=$started frameCount=$frameCount)")
        runCatching { log?.close() }
        log = null

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
