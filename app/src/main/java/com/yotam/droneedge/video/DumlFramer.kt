package com.droneedge.app.video

import java.io.ByteArrayOutputStream

data class DumlPacket(
    val src: Int,
    val dst: Int,
    val seq: Int,
    val attributes: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DumlPacket) return false
        return src == other.src && dst == other.dst && seq == other.seq &&
               attributes == other.attributes && cmdSet == other.cmdSet &&
               cmdId == other.cmdId && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var h = src
        h = 31 * h + dst; h = 31 * h + seq
        h = 31 * h + attributes; h = 31 * h + cmdSet; h = 31 * h + cmdId
        return 31 * h + payload.contentHashCode()
    }
}

class DumlFramer {

    private val buffer = ByteArrayOutputStream(4096)

    /**
     * Feed raw bytes from one USB bulk read. Returns a parsed [DumlPacket] when a
     * complete, CRC-valid packet is in the buffer; null otherwise. Automatically
     * resyncs on bad magic byte, bad CRC8, or bad CRC16 by discarding the offending
     * SOF byte and searching for the next 0x55.
     *
     * Passing length=0 is valid — it drains any packet already buffered from prior
     * calls (useful when two packets arrived in one bulk read).
     */
    fun feed(bytes: ByteArray, length: Int): DumlPacket? {
        if (length > 0) buffer.write(bytes, 0, minOf(length, bytes.size))
        return tryParse()
    }

    private fun tryParse(): DumlPacket? {
        val buf = buffer.toByteArray()
        var offset = 0

        while (offset < buf.size) {
            if (buf[offset] != 0x55.toByte()) { offset++; continue }
            if (buf.size - offset < 4) break

            val totalLen = (buf[offset + 1].toInt() and 0xFF) or
                           ((buf[offset + 2].toInt() and 0x03) shl 8)
            if (totalLen < 13) { offset++; continue }

            if (computeCrc8(buf, offset, 3) != (buf[offset + 3].toInt() and 0xFF)) {
                offset++; continue
            }
            if (buf.size - offset < totalLen) break

            val crc16Actual   = computeCrc16(buf, offset, totalLen - 2)
            val crc16Expected = ((buf[offset + totalLen - 2].toInt() and 0xFF) or
                                ((buf[offset + totalLen - 1].toInt() and 0xFF) shl 8))
            if (crc16Actual != crc16Expected) { offset++; continue }

            val payloadLen = totalLen - 13
            val packet = DumlPacket(
                src        = buf[offset + 4].toInt() and 0xFF,
                dst        = buf[offset + 5].toInt() and 0xFF,
                seq        = (buf[offset + 6].toInt() and 0xFF) or
                             ((buf[offset + 7].toInt() and 0xFF) shl 8),
                attributes = buf[offset + 8].toInt() and 0xFF,
                cmdSet     = buf[offset + 9].toInt() and 0xFF,
                cmdId      = buf[offset + 10].toInt() and 0xFF,
                payload    = if (payloadLen > 0)
                                 buf.copyOfRange(offset + 11, offset + 11 + payloadLen)
                             else byteArrayOf(),
            )
            buffer.reset()
            val tail = offset + totalLen
            if (tail < buf.size) buffer.write(buf, tail, buf.size - tail)
            return packet
        }

        buffer.reset()
        if (offset < buf.size) buffer.write(buf, offset, buf.size - offset)
        return null
    }

    /** Build a ready-to-send DUML packet with correct CRC8 and CRC16. */
    fun buildPacket(
        src: Int, dst: Int, seq: Int,
        cmdSet: Int, cmdId: Int,
        payload: ByteArray = byteArrayOf(),
        needAck: Boolean = false,
    ): ByteArray {
        val totalLen = 13 + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0]  = 0x55.toByte()
        pkt[1]  = (totalLen and 0xFF).toByte()
        pkt[2]  = ((totalLen shr 8) and 0x03).toByte()
        pkt[3]  = computeCrc8(pkt, 0, 3).toByte()
        pkt[4]  = (src    and 0xFF).toByte()
        pkt[5]  = (dst    and 0xFF).toByte()
        pkt[6]  = (seq    and 0xFF).toByte()
        pkt[7]  = ((seq   shr 8) and 0xFF).toByte()
        pkt[8]  = (if (needAck) 0x80 else 0x00).toByte()
        pkt[9]  = (cmdSet and 0xFF).toByte()
        pkt[10] = (cmdId  and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(pkt, 11)
        val crc16 = computeCrc16(pkt, 0, totalLen - 2)
        pkt[totalLen - 2] = (crc16        and 0xFF).toByte()
        pkt[totalLen - 1] = ((crc16 shr 8) and 0xFF).toByte()
        return pkt
    }

    fun reset() { buffer.reset() }

    companion object {
        // CRC8: polynomial 0x31 (Maxim/Dallas 1-Wire), init 0x77, no reflection
        private val CRC8_TABLE = IntArray(256).also { t ->
            for (i in 0..255) {
                var c = i
                repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x31 else c shl 1 }
                t[i] = c and 0xFF
            }
        }

        fun computeCrc8(data: ByteArray, offset: Int, count: Int): Int {
            var crc = 0x77
            for (i in offset until offset + count)
                crc = CRC8_TABLE[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
            return crc
        }

        // CRC16: polynomial 0x1021 (CRC-16/CCITT-FALSE), init 0xFFFF, no reflection
        private val CRC16_TABLE = IntArray(256).also { t ->
            for (i in 0..255) {
                var c = i shl 8
                repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
                t[i] = c and 0xFFFF
            }
        }

        fun computeCrc16(data: ByteArray, offset: Int, count: Int): Int {
            var crc = 0xFFFF
            for (i in offset until offset + count)
                crc = ((crc shl 8) xor CRC16_TABLE[((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
            return crc
        }
    }
}
