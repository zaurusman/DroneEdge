package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import com.droneedge.app.recording.yuv420ToArgbPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams H.264 video from DJI Goggles 2 / Integra via USB Accessory mode.
 *
 * Protocol: logiclink framing over USB AOA.
 *   Header: 0x55 0xCC + port (2 bytes LE) + length (4 bytes LE) = 8 bytes total.
 *   Port 0x574A = raw H.264 video IN from goggles.
 *
 * Decoding: VIDEO_IN bytes are written to a PipedOutputStream. ExoPlayer reads
 * from the matching PipedInputStream via PipeDataSource + RawH264Extractor,
 * which uses ExoPlayer's built-in H264Reader for proper NAL-unit parsing and
 * renders directly to the provided Surface. No manual MediaCodec management.
 *
 * Based on the approach from fpv-wtf/voc-poc + d4rken/fpv-dvca.
 */
@OptIn(UnstableApi::class)
class DjiGogglesAccessorySource(
    private val context: Context,
    val accessory: UsbAccessory,
    private val renderSurface: android.view.Surface? = null,
) : VideoSource {

    @Volatile override var width: Int = 1920
        private set
    @Volatile override var height: Int = 1080
        private set

    @Volatile private var running    = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = channelFlow {
        val log = DjiGogglesVideoSource.openLogWriter(context)
        log?.println("=== DjiGogglesAccessorySource ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
        log?.println("manufacturer=${accessory.manufacturer}  model=${accessory.model}  version=${accessory.version}")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val pfd = usbManager.openAccessory(accessory)
        if (pfd == null) {
            log?.println("ERROR: openAccessory returned null")
            log?.close()
            error("Cannot open DJI Goggles accessory — permission denied or already claimed")
        }

        val inputStream  = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        log?.println("Accessory opened OK")

        // H.264 pipe: USB loop writes VIDEO_IN bytes here; ExoPlayer reads the other end.
        val h264PipeOut = PipedOutputStream()
        val h264PipeIn  = PipedInputStream(h264PipeOut, 1_048_576) // 1 MB cap (~1.6s at 5Mbps)

        // Inference decoder: second pipe + ImageReader at 10fps cap
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

        // ExoPlayer must live on a Looper thread (main).
        val playerJob = launch(Dispatchers.Main) {
            // Minimal buffer for live streaming — default is 50 s which causes ~10 s latency.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs             */ 200,
                    /* maxBufferMs             */ 400,
                    /* bufferForPlaybackMs     */ 50,
                    /* bufferForPlaybackAfterRebufferMs */ 100,
                )
                .build()
            val player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
            renderSurface?.let { player.setVideoSurface(it) }

            // Mirror ExoPlayer state and errors into the log file so we can debug without ADB.
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    val name = when (state) {
                        androidx.media3.common.Player.STATE_IDLE     -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY     -> "READY"
                        androidx.media3.common.Player.STATE_ENDED     -> "ENDED"
                        else -> "UNKNOWN($state)"
                    }
                    log?.println("ExoPlayer state → $name")
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    log?.println("ExoPlayer ERROR: ${error.errorCodeName} — ${error.message}")
                    error.cause?.let { log?.println("  cause: ${it.javaClass.simpleName}: ${it.message}") }
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    log?.println("ExoPlayer video size: ${videoSize.width}x${videoSize.height}")
                    width  = videoSize.width
                    height = videoSize.height
                }
                override fun onRenderedFirstFrame() {
                    log?.println("ExoPlayer rendered first frame")
                }
            })

            val dsFactory: DataSource.Factory = DataSource.Factory { PipeDataSource(h264PipeIn) }
            val exFactory: ExtractorsFactory  = ExtractorsFactory { arrayOf(RawH264Extractor()) }
            val source = ProgressiveMediaSource.Factory(dsFactory, exFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

            player.setMediaSource(source)
            player.prepare()
            player.play()
            log?.println("ExoPlayer started (surface=${renderSurface != null})")

            try {
                awaitCancellation()
            } finally {
                player.release()
                log?.println("ExoPlayer released")
            }
        }

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

        val readBuf  = ByteArray(131_072)
        val pending  = ByteArrayOutputStream(131_072)
        var totalReads  = 0
        var totalFrames = 0
        var videoBytes  = 0L
        var lastActivateMs = 0L

        try {
            log?.println("entering read loop — sending initial activation")
            sendActivation(outputStream, log)
            lastActivateMs = System.currentTimeMillis()

            while (running && isActive) {
                val n = inputStream.read(readBuf)
                if (n <= 0) continue
                totalReads++

                if (totalReads <= 5) {
                    val hex = readBuf.take(minOf(n, 32)).joinToString(" ") { "%02x".format(it) }
                    log?.println("raw[$totalReads] n=$n [$hex]")
                }

                val now = System.currentTimeMillis()
                if (now - lastActivateMs > 5_000L) {
                    sendActivation(outputStream, log)
                    lastActivateMs = now
                }

                pending.write(readBuf, 0, n)
                val data = pending.toByteArray()
                pending.reset()

                var i = 0
                while (i < data.size) {
                    if (i + HEADER_SIZE > data.size) {
                        pending.write(data, i, data.size - i)
                        break
                    }
                    if (data[i] != 0x55.toByte() || data[i + 1] != 0xCC.toByte()) {
                        i++; continue
                    }

                    val port = (data[i + 2].toInt() and 0xFF) or
                               ((data[i + 3].toInt() and 0xFF) shl 8)
                    val length = (data[i + 4].toInt() and 0xFF) or
                                 ((data[i + 5].toInt() and 0xFF) shl 8) or
                                 ((data[i + 6].toInt() and 0xFF) shl 16) or
                                 ((data[i + 7].toInt() and 0xFF) shl 24)

                    if (length < 0 || length > 512_000) { i++; continue }
                    if (i + HEADER_SIZE + length > data.size) {
                        pending.write(data, i, data.size - i)
                        break
                    }

                    if (port == VIDEO_IN && length > 0) {
                        h264PipeOut.write(data, i + HEADER_SIZE, length)
                        h264PipeOut.flush()
                        runCatching {
                            inferPipeOut.write(data, i + HEADER_SIZE, length)
                            inferPipeOut.flush()
                        }
                        videoBytes += length
                        if (videoBytes <= 32_768L || videoBytes % 1_048_576L < length) {
                            log?.println("video pipe: wrote ${length}B (total ${videoBytes / 1024}KB) at rx#$totalFrames")
                        }
                        val bmp = pendingInferenceBitmap.getAndSet(null)
                        send(VideoFrame(frameIndex++, System.currentTimeMillis(), width, height, bmp))
                    }

                    i += HEADER_SIZE + length
                    totalFrames++
                }
            }

            log?.println("--- session summary ---")
            log?.println("totalReads=$totalReads  totalFrames=$totalFrames  videoBytes=${videoBytes / 1024}KB")

        } finally {
            inferPlayerJob.cancel()
            inferHandlerThread.quitSafely()
            pendingInferenceBitmap.getAndSet(null)?.recycle()
            runCatching { inferPipeOut.close() }
            inferImageReader.close()
            playerJob.cancel()
            log?.println("=== session ended ===")
            log?.close()
            runCatching { h264PipeOut.close() }
            inputStream.close()
            outputStream.close()
            pfd.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    private fun sendActivation(out: FileOutputStream, log: java.io.PrintWriter?) {
        runCatching { out.write(CMD_VIDEO_ACTIVATE_1) }
            .onSuccess { log?.println("tx activate-1 (camcap_common subscription)") }
            .onFailure { log?.println("tx activate-1 FAILED: ${it.message}") }
        runCatching { out.write(CMD_VIDEO_ACTIVATE_2) }
            .onSuccess { log?.println("tx activate-2 (APP keep-alive)") }
            .onFailure { log?.println("tx activate-2 FAILED: ${it.message}") }
    }

    companion object {
        private const val TAG         = "DjiGogglesAccessory"
        private const val HEADER_SIZE = 8

        private const val CTRL_IN  = 0x7530
        const val VIDEO_IN         = 0x574A
        private const val CTRL_OUT = 0x5749

        fun isDjiAccessory(acc: UsbAccessory): Boolean =
            acc.manufacturer?.contains("DJI", ignoreCase = true) == true ||
            acc.model?.contains("DJI", ignoreCase = true) == true ||
            acc.model?.contains("Goggles", ignoreCase = true) == true

        private val CMD_VIDEO_ACTIVATE_1 = byteArrayOf(
            0x55, 0xCC.toByte(), 0x49, 0x57, 0x2D, 0x00, 0x00, 0x00,
            0x55, 0x2D, 0x04, 0xF2.toByte(), 0x02, 0x28, 0xF3.toByte(), 0xFE.toByte(),
            0x40, 0x00, 0x99.toByte(),
            0x02, 0x02, 0x00, 0x00, 0xD5.toByte(), 0x07, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x13, 0x00, 0x0D, 0x00,
            0x63, 0x61, 0x6D, 0x63, 0x61, 0x70, 0x5F,
            0x63, 0x6F, 0x6D, 0x6D, 0x6F, 0x6E,
            0x00, 0x00, 0x00, 0x00, 0xD0.toByte(), 0x93.toByte(),
            0x92.toByte(), 0x3A
        )

        private val CMD_VIDEO_ACTIVATE_2 = byteArrayOf(
            0x55, 0xCC.toByte(), 0x49, 0x57, 0x1B, 0x00, 0x00, 0x00,
            0x55, 0x1B, 0x04, 0x75, 0x02, 0x3C, 0xF4.toByte(), 0xFE.toByte(),
            0x40, 0x00, 0x88.toByte(),
            0x17, 0x00, 0x00, 0x23, 0x00,
            0x41, 0x50, 0x50,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x58, 0xA6.toByte(),
            0x34, 0x18
        )
    }
}
