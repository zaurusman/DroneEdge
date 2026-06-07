package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.droneedge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DjiGogglesVideoSource(
    private val context: Context,
    val device: UsbDevice,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val log = openLogWriter(context)
        Log.i(TAG, "DJI log → ${logFilePath(context)}")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        log?.appendLine("=== DroneEdge DJI session $ts ===")
        log?.appendLine("device: ${device.deviceName}  vendorId=0x%04x  productId=0x%04x"
            .format(device.vendorId, device.productId))
        log?.appendLine("interfaceCount=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log?.appendLine("  iface[$i] class=0x%02x sub=0x%02x proto=0x%02x endpoints=${iface.endpointCount}"
                .format(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val info = findBulkInterface(device)
        if (info == null) {
            log?.appendLine("ERROR: no bulk interface found — aborting")
            log?.flush(); log?.close()
            error("DJI device connected but no streaming interface found")
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            log?.appendLine("ERROR: openDevice returned null (permission denied?)")
            log?.flush(); log?.close()
            error("USB permission denied for DJI Goggles")
        }

        val codec = try {
            MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                c.configure(fmt, null, null, 0)
                c.start()
            }
        } catch (e: Exception) {
            log?.appendLine("ERROR: H.264 decoder init failed: ${e.message}")
            log?.flush(); log?.close()
            connection.close()
            error("Could not initialise H.264 decoder")
        }

        try {
            val claimed = connection.claimInterface(info.iface, true)
            log?.appendLine("claimInterface(${info.ifaceNumber}): $claimed")
            check(claimed) { "Cannot claim DJI streaming interface" }
            log?.appendLine("sending startup sequence…")
            sendStartupSequence(connection, info)
            log?.appendLine("startup sequence sent — waiting for packets")

            val framer  = DumlFramer()
            val buf     = ByteArray(16_384)
            val bufInfo = MediaCodec.BufferInfo()
            var consecutiveErrors = 0

            while (running && currentCoroutineContext().isActive) {
                val len = connection.bulkTransfer(info.endpointIn, buf, buf.size, 1_000)
                if (len < 0) {
                    if (++consecutiveErrors >= 10) error("DJI video stream lost")
                    continue
                }
                consecutiveErrors = 0

                val packet = framer.feed(buf, len) ?: continue

                val payHex = packet.payload.take(16).joinToString(" ") { "0x%02x".format(it) }
                val logLine = "DUML src=0x%02x dst=0x%02x cmdSet=0x%02x cmdId=0x%02x payLen=%d  [%s]"
                    .format(packet.src, packet.dst, packet.cmdSet, packet.cmdId, packet.payload.size, payHex)
                log?.appendLine(logLine)
                if (BuildConfig.DEBUG) Log.d(TAG, logLine)

                if (packet.cmdSet != VIDEO_CMD_SET || packet.cmdId != VIDEO_CMD_ID) continue
                log?.appendLine("  ^^^ VIDEO PACKET — matched cmdSet=0x%02x cmdId=0x%02x".format(VIDEO_CMD_SET, VIDEO_CMD_ID))
                val nal = packet.payload
                if (nal.isEmpty()) continue

                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex < 0) continue
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                inputBuf.clear()
                inputBuf.put(nal)
                val flags = if (isSpsPpsNal(nal)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                codec.queueInputBuffer(inputIndex, 0, nal.size, System.currentTimeMillis() * 1000L, flags)

                var outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            width  = fmt.getInteger(MediaFormat.KEY_WIDTH)
                            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
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
        } finally {
            log?.appendLine("=== session ended ===")
            log?.flush()
            log?.close()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            connection.releaseInterface(info.iface)
            connection.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    private data class StreamInfo(
        val iface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint?,
        val ifaceNumber: Int,
    )

    private fun findBulkInterface(dev: UsbDevice): StreamInfo? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn != null) return StreamInfo(iface, epIn, epOut, iface.id)
        }
        return null
    }

    private fun sendStartupSequence(
        connection: android.hardware.usb.UsbDeviceConnection,
        info: StreamInfo,
    ) {
        val epOut = info.endpointOut ?: return
        val framer = DumlFramer()
        val ping = framer.buildPacket(
            src = SRC_MOBILE_APP, dst = DST_GOGGLES, seq = 0,
            cmdSet = CMD_SET_GENERAL, cmdId = CMD_ID_PING, needAck = true,
        )
        connection.bulkTransfer(epOut, ping, ping.size, 2_000)
        val startStream = framer.buildPacket(
            src = SRC_MOBILE_APP, dst = DST_GOGGLES, seq = 1,
            cmdSet = CMD_SET_VIDEO, cmdId = CMD_ID_START_STREAM, needAck = true,
        )
        connection.bulkTransfer(epOut, startStream, startStream.size, 2_000)
    }

    private fun isSpsPpsNal(nal: ByteArray): Boolean {
        val offset = when {
            nal.size > 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size > 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> return false
        }
        val nalType = nal[offset].toInt() and 0x1F
        return nalType == 7 || nalType == 8
    }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap? {
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yRowStride    = yPlane.rowStride
            val uvRowStride   = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, row * w, w)
            }
            val uvH = h / 2; val uvW = w / 2
            for (row in 0 until uvH) {
                for (col in 0 until uvW) {
                    val src = row * uvRowStride + col * uvPixelStride
                    val dst = w * h + row * w + col * 2
                    vBuf.position(src); nv21[dst]     = vBuf.get()
                    uBuf.position(src); nv21[dst + 1] = uBuf.get()
                }
            }
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 90, out)
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.e(TAG, "YUV→Bitmap conversion failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DjiGogglesVideoSource"

        /** DJI USB vendor ID (0x2CA3 = 11427 decimal). Used in LiveScreen and LiveViewModel. */
        const val VENDOR_ID = 0x2CA3

        private const val SRC_MOBILE_APP      = 0x06
        private const val DST_GOGGLES         = 0x07
        private const val CMD_SET_GENERAL     = 0x00
        private const val CMD_ID_PING         = 0x00
        private const val CMD_SET_VIDEO       = 0x09
        private const val CMD_ID_START_STREAM = 0x09

        // Video data packets: cmdSet + cmdId that carry H.264 NAL units.
        // These are placeholder values based on community research.
        // Update after inspecting Logcat output on first hardware connection.
        const val VIDEO_CMD_SET = 0x09
        const val VIDEO_CMD_ID  = 0x00

        fun openLogWriter(context: Context): PrintWriter? {
            // Try public Documents/DroneEdge/logs/ first (visible in Files app).
            // Fall back to app-private storage if MANAGE_EXTERNAL_STORAGE wasn't granted.
            val publicDir = com.droneedge.app.MainActivity.droneEdgeLogsDir()
            val file = if (runCatching { publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
                File(publicDir, "dji_duml_log.txt")
            } else {
                File(context.getExternalFilesDir("logs").also { it?.mkdirs() }, "dji_duml_log.txt")
            }
            // autoFlush=true: every println() goes to disk immediately.
            // Without this, data stays in the buffer if the process is killed.
            return runCatching { PrintWriter(FileWriter(file, false), true) }
                .onFailure { Log.e(TAG, "Cannot open log file at ${file.absolutePath}: ${it.message}") }
                .getOrNull()
        }

        fun logFilePath(context: Context): String {
            val publicDir = com.droneedge.app.MainActivity.droneEdgeLogsDir()
            return if (runCatching { publicDir.canWrite() }.getOrDefault(false))
                File(publicDir, "dji_duml_log.txt").absolutePath
            else
                File(context.getExternalFilesDir("logs"), "dji_duml_log.txt").absolutePath
        }
    }
}
