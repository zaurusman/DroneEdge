package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Streams H.264 video from DJI Goggles 2 / Integra via USB Accessory mode.
 *
 * Protocol: logiclink framing over USB AOA.
 *   Header: 0x55 0xCC + port (2 bytes LE) + length (4 bytes LE) = 8 bytes total.
 *   Port 0x574A = raw H.264 video IN from goggles.
 *
 * Decoding: VIDEO_IN bytes are fed straight into a hardware MediaCodec (video/avc)
 * configured on the display Surface. Each decoded frame is rendered immediately via
 * releaseOutputBuffer(idx, true) — no player, no buffering, minimal latency. This
 * replaced an ExoPlayer + PipedStream pipeline whose buffering added latency and
 * dropped frames under load. Mirrors the proven MediaCodec loop in DjiGogglesVideoSource.
 *
 * Inference: PixelCopy captures the rendered surface at ~20fps into a 640×360 Bitmap
 * and attaches it to VideoFrame.bitmap for TfliteDetector.
 */
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

        // PixelCopy inference: capture the already-rendered display surface.
        // Avoids a second hardware decoder whose YUV_420_888 Surface output is not
        // guaranteed across Android devices/drivers.
        val pendingInferenceBitmap = AtomicReference<Bitmap?>(null)
        var pixelCopyCount = 0L
        val pixelCopyJob = if (renderSurface != null) launch(Dispatchers.IO) {
            val handlerThread = HandlerThread("pixelcopy-infer")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)
            val inferBitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            try {
                while (isActive) {
                    // ~20fps capture cap so the inference loop (≈95ms), not the bitmap
                    // supply, is the limiter. Stale bitmaps are dropped via getAndSet.
                    delay(50L)
                    suspendCancellableCoroutine<Unit> { cont ->
                        PixelCopy.request(
                            renderSurface, null, inferBitmap,
                            { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    pixelCopyCount++
                                    if (pixelCopyCount == 1L || pixelCopyCount % 100L == 0L) {
                                        log?.println("PixelCopy inference: frame #$pixelCopyCount")
                                    }
                                    val copy = inferBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    pendingInferenceBitmap.getAndSet(copy)?.recycle()
                                } else {
                                    log?.println("PixelCopy inference: failed result=$result")
                                }
                                cont.resume(Unit)
                            },
                            handler,
                        )
                    }
                }
            } finally {
                inferBitmap.recycle()
                pendingInferenceBitmap.getAndSet(null)?.recycle()
                handlerThread.quitSafely()
            }
        } else null

        // Hardware H.264 decoder rendering straight to the display surface. Configured
        // without codec-specific data — MediaCodec picks up in-band SPS/PPS from the
        // DJI stream (sent periodically before each IDR).
        val codec: MediaCodec? = if (renderSurface != null) {
            try {
                MediaCodec.createDecoderByType("video/avc").also { c ->
                    val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                    c.configure(fmt, renderSurface, null, 0)
                    c.start()
                }
            } catch (e: Exception) {
                log?.println("ERROR: H.264 decoder init failed: ${e.message}")
                null
            }
        } else {
            log?.println("WARN: no render surface — decoding/display disabled")
            null
        }
        log?.println("MediaCodec ready=${codec != null} (${width}x${height})")

        val readBuf  = ByteArray(131_072)
        val pending  = ByteArrayOutputStream(131_072)
        val bufInfo  = MediaCodec.BufferInfo()
        // Reassembles H.264 NAL units across the DJI's 4KB logiclink chunks. MediaCodec
        // needs each input buffer to start at a NAL boundary (00 00 01); raw chunks don't.
        val videoAcc = ByteArrayOutputStream(262_144)
        var aligned  = false
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
                        if (codec != null) {
                            // Accumulate, then feed only complete NAL units (each starting at a
                            // 00 00 01 start code). Keep the trailing partial NAL for next time.
                            videoAcc.write(data, i + HEADER_SIZE, length)
                            val vbuf = videoAcc.toByteArray()
                            videoAcc.reset()

                            var alignStart = 0
                            if (!aligned) {
                                val sc = nextStartCode(vbuf, 0, vbuf.size)
                                if (sc < 0) { videoAcc.write(vbuf, 0, vbuf.size); alignStart = -1 }
                                else { aligned = true; alignStart = sc }
                            }
                            if (alignStart >= 0) {
                                val lastSc = lastStartCode(vbuf, alignStart + 3, vbuf.size)
                                if (lastSc > alignStart) {
                                    var ns = alignStart
                                    while (ns < lastSc) {
                                        val nx = nextStartCode(vbuf, ns + 3, lastSc)
                                        val ne = if (nx < 0) lastSc else nx
                                        val inputIdx = try { codec.dequeueInputBuffer(10_000) }
                                                       catch (e: IllegalStateException) { -1 }
                                        if (inputIdx >= 0) {
                                            val ib = codec.getInputBuffer(inputIdx)
                                            if (ib != null) {
                                                ib.clear()
                                                runCatching { ib.put(vbuf, ns, ne - ns) }
                                                codec.queueInputBuffer(inputIdx, 0, ne - ns, System.nanoTime() / 1000, 0)
                                            }
                                        }
                                        ns = ne
                                    }
                                    videoAcc.write(vbuf, lastSc, vbuf.size - lastSc)
                                } else {
                                    videoAcc.write(vbuf, alignStart, vbuf.size - alignStart)
                                }
                            }

                            // Render every available decoded frame immediately (low latency).
                            var outIdx = try { codec.dequeueOutputBuffer(bufInfo, 0) }
                                         catch (e: IllegalStateException) { MediaCodec.INFO_TRY_AGAIN_LATER }
                            while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                                when {
                                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                        val fmt = codec.outputFormat
                                        width  = fmt.getInteger(MediaFormat.KEY_WIDTH)
                                        height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                                        log?.println("output format changed: ${width}x${height}")
                                    }
                                    outIdx >= 0 -> codec.releaseOutputBuffer(outIdx, true) // render
                                }
                                outIdx = try { codec.dequeueOutputBuffer(bufInfo, 0) }
                                         catch (e: IllegalStateException) { MediaCodec.INFO_TRY_AGAIN_LATER }
                            }
                        }

                        videoBytes += length
                        if (videoBytes <= 32_768L || videoBytes % 1_048_576L < length) {
                            log?.println("video: ${length}B (total ${videoBytes / 1024}KB) at rx#$totalFrames")
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
            pixelCopyJob?.cancel()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            log?.println("=== session ended ===")
            log?.close()
            inputStream.close()
            outputStream.close()
            pfd.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    /** Index of the next 00 00 01 start code in [from, end), or -1. */
    private fun nextStartCode(b: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    /** Index of the last 00 00 01 start code in [from, end), or -1. */
    private fun lastStartCode(b: ByteArray, from: Int, end: Int): Int {
        var i = end - 3
        while (i >= from) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
            i--
        }
        return -1
    }

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
