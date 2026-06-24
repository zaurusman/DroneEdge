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
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outIdx >= 0 -> {
                        val render = bufInfo.size > 0
                        val ptsUs = bufInfo.presentationTimeUs
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
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
