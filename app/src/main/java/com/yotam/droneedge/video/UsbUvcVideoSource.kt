package com.droneedge.app.video

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class UsbUvcVideoSource(
    private val context: Context,
    val device: UsbDevice,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L
    private val assembler = UvcFrameAssembler()

    override val frames: Flow<VideoFrame> = flow {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val info = findBulkStreamingInterface(device)
            ?: error("No UVC bulk streaming interface on ${device.deviceName}")
        val connection = usbManager.openDevice(device)
            ?: error("Cannot open ${device.deviceName} — USB permission not granted?")

        try {
            check(connection.claimInterface(info.iface, true)) {
                "Cannot claim UVC streaming interface"
            }
            commitMjpegStream(connection, info.ifaceNumber)
            assembler.reset()

            val buf = ByteArray(16_384)
            var consecutiveErrors = 0
            while (running) {
                val len = connection.bulkTransfer(info.endpoint, buf, buf.size, 1_000)
                if (len < 0) {
                    if (++consecutiveErrors >= 10) break
                    continue
                }
                consecutiveErrors = 0

                val jpeg = assembler.feed(buf, len) ?: continue
                val bmp  = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                width  = bmp.width
                height = bmp.height

                emit(
                    VideoFrame(
                        index       = frameIndex++,
                        timestampMs = System.currentTimeMillis(),
                        width       = bmp.width,
                        height      = bmp.height,
                        bitmap      = bmp,
                    )
                )
            }
        } finally {
            connection.releaseInterface(info.iface)
            connection.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }

    private data class StreamInfo(
        val iface: UsbInterface,
        val endpoint: UsbEndpoint,
        val ifaceNumber: Int,
    )

    private fun findBulkStreamingInterface(dev: UsbDevice): StreamInfo? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            // UVC Video Streaming: bInterfaceClass=0x0E, bInterfaceSubClass=0x02
            if (iface.interfaceClass == 0x0E && iface.interfaceSubclass == 0x02) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) return StreamInfo(iface, ep, iface.id)
                }
            }
        }
        return null
    }

    private fun commitMjpegStream(connection: UsbDeviceConnection, vsIfaceNumber: Int) {
        // 26-byte UVC 1.1 VS Probe/Commit control structure
        // Format index 1 = MJPEG (typical for UVC cameras), frame index 1, ~30 fps
        val ctrl = ByteArray(26).apply {
            this[0] = 0x01               // bmHint: negotiate dwFrameInterval
            this[2] = 0x01               // bFormatIndex (1 = first format, usually MJPEG)
            this[3] = 0x01               // bFrameIndex  (1 = first frame descriptor)
            val interval = 333_333       // dwFrameInterval in 100ns units (≈30 fps)
            this[4] = (interval         and 0xFF).toByte()
            this[5] = (interval.shr(8)  and 0xFF).toByte()
            this[6] = (interval.shr(16) and 0xFF).toByte()
            this[7] = (interval.shr(24) and 0xFF).toByte()
        }
        // bmRequestType=0x21 Class|Interface|H→D, bRequest=SET_CUR(0x01)
        // wValue=VS_COMMIT_CONTROL (selector 0x02 shifted to high byte = 0x0200)
        val result = connection.controlTransfer(0x21, 0x01, 0x0200, vsIfaceNumber, ctrl, ctrl.size, 1_000)
        check(result >= 0) { "VS_COMMIT_CONTROL failed (result=$result) — camera may not support MJPEG format index 1" }
    }
}
