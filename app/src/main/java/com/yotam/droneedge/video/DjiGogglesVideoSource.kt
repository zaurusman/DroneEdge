package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Streams H.264 video from DJI Goggles 2 / Integra via USB.
 *
 * Protocol discovered by the voc-poc / DigiView-Android community projects:
 *   - Claim interface 3 on the device (vendor 0x2CA3, product 0x001F)
 *   - Send "RMVT" (4 bytes) to bulk-out endpoint 0 to start the stream
 *   - Read raw H.264 elementary stream from bulk-in endpoint 1
 *   - Resend "RMVT" whenever a read returns empty to recover the stream
 */
class DjiGogglesVideoSource(
    private val context: Context,
    val device: UsbDevice,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var running     = false
    @Volatile private var frameIndex  = 0L

    override val frames: Flow<VideoFrame> = flow {
        val log = openLogWriter(context)
        log?.println("=== DjiGogglesVideoSource session ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
        log?.println("device: ${device.deviceName}  vendor=0x%04x  product=0x%04x  interfaces=${device.interfaceCount}"
            .format(device.vendorId, device.productId))

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            log?.println("ERROR: openDevice returned null — permission denied?")
            log?.close()
            error("USB permission denied for DJI Goggles")
        }

        if (device.interfaceCount <= INTERFACE_INDEX) {
            log?.println("ERROR: device only has ${device.interfaceCount} interfaces, need ${INTERFACE_INDEX + 1}")
            connection.close(); log?.close()
            error("DJI Goggles interface $INTERFACE_INDEX not found (only ${device.interfaceCount} interfaces)")
        }

        val iface = device.getInterface(INTERFACE_INDEX)
        log?.println("interface $INTERFACE_INDEX: class=0x%02x sub=0x%02x endpoints=${iface.endpointCount}"
            .format(iface.interfaceClass, iface.interfaceSubclass))

        val claimed = connection.claimInterface(iface, true)
        log?.println("claimInterface: $claimed")
        if (!claimed) {
            connection.close(); log?.close()
            error("Cannot claim DJI Goggles interface $INTERFACE_INDEX")
        }

        if (iface.endpointCount < 2) {
            log?.println("ERROR: interface has only ${iface.endpointCount} endpoints, need 2")
            connection.releaseInterface(iface); connection.close(); log?.close()
            error("DJI Goggles interface has insufficient endpoints")
        }

        val epOut = iface.getEndpoint(BULK_OUT_ENDPOINT_IDX)
        val epIn  = iface.getEndpoint(BULK_IN_ENDPOINT_IDX)
        log?.println("epOut dir=${epOut.direction}  epIn dir=${epIn.direction}")

        // Send the magic packet to start the H.264 stream.
        val sent = connection.bulkTransfer(epOut, MAGIC_PACKET, MAGIC_PACKET.size, WRITE_TIMEOUT_MS)
        log?.println("RMVT magic packet sent: $sent bytes")

        val codec = try {
            MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                c.configure(fmt, null, null, 0)
                c.start()
            }
        } catch (e: Exception) {
            log?.println("ERROR: H.264 decoder init failed: ${e.message}")
            log?.close()
            connection.releaseInterface(iface); connection.close()
            error("Could not initialise H.264 decoder")
        }

        val readBuf = ByteArray(READ_BUFFER_SIZE)
        val bufInfo = MediaCodec.BufferInfo()
        var totalPackets = 0
        var emptyReads   = 0

        try {
            log?.println("entering read loop")
            while (running && currentCoroutineContext().isActive) {
                val len = connection.bulkTransfer(epIn, readBuf, readBuf.size, READ_TIMEOUT_MS)

                if (len <= 0) {
                    emptyReads++
                    if (emptyReads % 10 == 0) log?.println("empty read #$emptyReads — resending RMVT")
                    // Resend magic packet to recover the stream (per DigiView-Android)
                    connection.bulkTransfer(epOut, MAGIC_PACKET, MAGIC_PACKET.size, WRITE_TIMEOUT_MS)
                    continue
                }

                emptyReads = 0
                totalPackets++
                if (totalPackets <= 5 || totalPackets % 100 == 0) {
                    val preview = readBuf.take(8).joinToString(" ") { "0x%02x".format(it) }
                    log?.println("pkt #$totalPackets len=$len  [${preview}]")
                }

                // Feed raw H.264 to the decoder
                val inputIdx = codec.dequeueInputBuffer(10_000)
                if (inputIdx >= 0) {
                    val buf = codec.getInputBuffer(inputIdx)!!
                    buf.clear()
                    buf.put(readBuf, 0, len)
                    codec.queueInputBuffer(inputIdx, 0, len, System.currentTimeMillis() * 1_000L, 0)
                }

                // Drain decoded output frames
                var outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            width  = fmt.getInteger(MediaFormat.KEY_WIDTH)
                            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                            log?.println("output format changed: ${width}x${height}")
                        }
                        outIdx >= 0 -> {
                            val image = codec.getOutputImage(outIdx)
                            val bmp   = if (image != null) imageToBitmap(image, width, height) else null
                            image?.close()
                            codec.releaseOutputBuffer(outIdx, false)
                            if (bmp != null) emit(VideoFrame(
                                index       = frameIndex++,
                                timestampMs = System.currentTimeMillis(),
                                width       = width,
                                height      = height,
                                bitmap      = bmp,
                            ))
                        }
                    }
                    outIdx = codec.dequeueOutputBuffer(bufInfo, 0)
                }
            }
            log?.println("loop exited normally. totalPackets=$totalPackets")
        } finally {
            log?.println("=== session ended  totalPackets=$totalPackets ===")
            log?.close()
            runCatching { codec.stop() }
            codec.release()
            connection.releaseInterface(iface)
            connection.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap? = try {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * w, w)
        }
        val uvH = h / 2; val uvW = w / 2
        for (row in 0 until uvH) for (col in 0 until uvW) {
            val src = row * uvRowStride + col * uvPixelStride
            val dst = w * h + row * w + col * 2
            vBuf.position(src); nv21[dst]     = vBuf.get()
            uBuf.position(src); nv21[dst + 1] = uBuf.get()
        }
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, w, h), 90, out)
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    } catch (e: Exception) {
        Log.e(TAG, "YUV→Bitmap failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "DjiGogglesVideoSource"

        /** DJI USB vendor ID (0x2CA3 = 11427). */
        const val VENDOR_ID  = 0x2CA3
        /** DJI Goggles 2 / Integra product ID (0x001F = 31), confirmed by voc-poc project. */
        const val PRODUCT_ID = 0x001F

        // voc-poc / DigiView-Android protocol constants
        private val MAGIC_PACKET       = "RMVT".toByteArray()
        private const val INTERFACE_INDEX       = 3
        private const val BULK_OUT_ENDPOINT_IDX = 0
        private const val BULK_IN_ENDPOINT_IDX  = 1
        private const val READ_BUFFER_SIZE      = 131_072
        private const val READ_TIMEOUT_MS       = 100
        private const val WRITE_TIMEOUT_MS      = 2_000

        fun openLogWriter(context: Context): PrintWriter? {
            val dir = com.droneedge.app.MainActivity.droneEdgeLogsDir().also { it.mkdirs() }
            val file = if (runCatching { dir.canWrite() }.getOrDefault(false))
                File(dir, "dji_duml_log.txt")
            else
                File(context.getExternalFilesDir("logs").also { it?.mkdirs() }, "dji_duml_log.txt")
            return runCatching { PrintWriter(FileWriter(file, false), true) }
                .onFailure { Log.e(TAG, "Cannot open log: ${it.message}") }
                .getOrNull()
        }

        fun logFilePath(context: Context): String {
            val dir = com.droneedge.app.MainActivity.droneEdgeLogsDir()
            return File(if (runCatching { dir.canWrite() }.getOrDefault(false)) dir
                        else (context.getExternalFilesDir("logs") ?: dir),
                        "dji_duml_log.txt").absolutePath
        }
    }
}
