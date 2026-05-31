package com.yotam.droneedge.video

import java.io.ByteArrayOutputStream

class UvcFrameAssembler {

    private var lastFid: Int = -1
    private val buffer = ByteArrayOutputStream(512 * 1024)

    /**
     * Feed one raw USB bulk-transfer packet. Returns assembled MJPEG frame bytes
     * when the EOF bit is set on the final packet of a frame; null otherwise.
     *
     * Thread-unsafe — call only from the IO coroutine in UsbUvcVideoSource.
     */
    fun feed(packet: ByteArray, length: Int): ByteArray? {
        if (length < 2) return null
        if (length > packet.size) return null  // guard against caller passing length > array size
        val headerLen = packet[0].toInt() and 0xFF
        if (headerLen < 2 || headerLen > length) return null

        val bmHeaderInfo = packet[1].toInt() and 0xFF
        val fid  = bmHeaderInfo and 0x01
        val eof  = (bmHeaderInfo and 0x02) != 0
        val err  = (bmHeaderInfo and 0x40) != 0

        if (err) { reset(); return null }

        if (lastFid != -1 && fid != lastFid) {
            // Frame ID toggled before EOF — previous frame was corrupted; discard
            buffer.reset()
        }
        lastFid = fid

        val payloadLen = length - headerLen
        if (payloadLen > 0) buffer.write(packet, headerLen, payloadLen)

        return if (eof && buffer.size() > 0) {
            buffer.toByteArray().also { buffer.reset() }
        } else null
    }

    fun reset() {
        lastFid = -1
        buffer.reset()
    }
}
