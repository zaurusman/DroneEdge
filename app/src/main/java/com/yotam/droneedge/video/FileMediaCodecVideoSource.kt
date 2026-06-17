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

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    override val frames: Flow<VideoFrame> = channelFlow {
        // Full-res PixelCopy capture of the rendered surface → inference + recording.
        val pending = AtomicReference<Bitmap?>(null)
        val captureJob = if (renderSurface != null) launch(Dispatchers.IO) {
            val ht = HandlerThread("file-pixelcopy").also { it.start() }
            val handler = Handler(ht.looper)
            val capBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                while (isActive) {
                    delay(15L) // intentional ~60fps cap for full-res file replay (higher than DJI source's 20fps cap); actual rate bounded by decode/display
                    suspendCancellableCoroutine<Unit> { cont ->
                        PixelCopy.request(renderSurface, capBitmap, { res ->
                            if (cont.isActive && res == PixelCopy.SUCCESS) {
                                pending.getAndSet(capBitmap.copy(Bitmap.Config.ARGB_8888, false))
                                    ?.recycle()
                            }
                            if (cont.isActive) cont.resume(Unit)
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
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = selectVideoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, renderSurface, null, 0)
                start()
            }
            val dec = codec

            val bufInfo = MediaCodec.BufferInfo()
            var anchorWallNs = System.nanoTime()
            var anchorPtsUs = -1L

            while (running && isActive) {
                // ── Feed one input buffer ──
                val inIdx = dec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val ib = dec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) {
                        // End of file → seamless loop. No EOS flag: we're looping, not
                        // terminating, so the codec keeps running for the next pass.
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        anchorPtsUs = -1L
                        dec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    } else {
                        val pts = extractor.sampleTime
                        dec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        extractor.advance()
                    }
                }

                // ── Drain one output buffer, pace, render ──
                val outIdx = dec.dequeueOutputBuffer(bufInfo, 10_000L)
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
                        dec.releaseOutputBuffer(outIdx, true) // render to surface
                        val bmp = pending.getAndSet(null)
                        send(VideoFrame(frameIndex++, System.currentTimeMillis(), width, height, bmp))
                    }
                }
            }
        } finally {
            captureJob?.cancel()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }.flowOn(Dispatchers.IO)

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

        /** Index of the first video track, or throws if none. */
        fun selectVideoTrack(extractor: MediaExtractor): Int {
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return i
            }
            error("No video track in $extractor")
        }
    }
}
