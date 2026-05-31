package com.yotam.droneedge.video

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UvcFrameAssemblerTest {

    private lateinit var assembler: UvcFrameAssembler

    @Before fun setUp() { assembler = UvcFrameAssembler() }

    /** Build a minimal 2-byte header + payload packet. */
    private fun packet(fid: Int, eof: Boolean, payload: ByteArray): ByteArray {
        val bmInfo = (fid and 0x01) or (if (eof) 0x02 else 0x00)
        return byteArrayOf(0x02.toByte(), bmInfo.toByte()) + payload
    }

    @Test fun `returns null for packet shorter than 2 bytes`() {
        assertNull(assembler.feed(byteArrayOf(0x01), 1))
    }

    @Test fun `returns null when header length exceeds packet length`() {
        // PHL=5 but only 3 bytes total
        val pkt = byteArrayOf(0x05, 0x02.toByte(), 0x01)
        assertNull(assembler.feed(pkt, pkt.size))
    }

    @Test fun `returns null for incomplete frame (no EOF)`() {
        val pkt = packet(fid = 0, eof = false, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertNull(assembler.feed(pkt, pkt.size))
    }

    @Test fun `returns payload bytes when EOF set on single packet`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAB.toByte())
        val pkt  = packet(fid = 0, eof = true, payload = jpeg)
        val result = assembler.feed(pkt, pkt.size)
        assertNotNull(result)
        assertArrayEquals(jpeg, result)
    }

    @Test fun `accumulates multi-packet frame then returns complete bytes on EOF`() {
        val part1 = byteArrayOf(0x01, 0x02, 0x03)
        val part2 = byteArrayOf(0x04, 0x05, 0x06)
        assertNull(assembler.feed(packet(fid = 0, eof = false, payload = part1), part1.size + 2))
        val result = assembler.feed(packet(fid = 0, eof = true, payload = part2), part2.size + 2)
        assertNotNull(result)
        assertArrayEquals(part1 + part2, result)
    }

    @Test fun `FID toggle discards previous partial frame, returns new frame on EOF`() {
        val partial = packet(fid = 0, eof = false, payload = byteArrayOf(0x01, 0x02))
        assembler.feed(partial, partial.size)
        val newPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val pkt = packet(fid = 1, eof = true, payload = newPayload)
        val result = assembler.feed(pkt, pkt.size)
        assertNotNull(result)
        assertArrayEquals(newPayload, result)
    }

    @Test fun `ERR bit triggers reset and returns null`() {
        val partial = packet(fid = 0, eof = false, payload = byteArrayOf(0x01))
        assembler.feed(partial, partial.size)
        // ERR bit (0x40) set in bmHeaderInfo
        val errPkt = byteArrayOf(0x02, 0x40.toByte())
        assertNull(assembler.feed(errPkt, errPkt.size))
        // Next frame starts fresh
        val fresh = byteArrayOf(0xCC.toByte())
        val result = assembler.feed(packet(fid = 0, eof = true, payload = fresh), fresh.size + 2)
        assertNotNull(result)
        assertArrayEquals(fresh, result)
    }

    @Test fun `reset clears accumulated state`() {
        assembler.feed(packet(fid = 0, eof = false, payload = byteArrayOf(0x01, 0x02)), 4)
        assembler.reset()
        val fresh = byteArrayOf(0xDD.toByte())
        val result = assembler.feed(packet(fid = 0, eof = true, payload = fresh), fresh.size + 2)
        assertNotNull(result)
        assertArrayEquals(fresh, result)
    }

    @Test fun `two consecutive complete frames both return correctly`() {
        // Frame 0 (fid=0)
        assertNull(assembler.feed(packet(fid = 0, eof = false, payload = byteArrayOf(0x01)), 3))
        val frame0 = assembler.feed(packet(fid = 0, eof = true, payload = byteArrayOf(0x02)), 3)
        assertArrayEquals(byteArrayOf(0x01, 0x02), frame0)

        // Frame 1 (fid=1) — FID toggles but buffer was already flushed by EOF
        assertNull(assembler.feed(packet(fid = 1, eof = false, payload = byteArrayOf(0x03)), 3))
        val frame1 = assembler.feed(packet(fid = 1, eof = true, payload = byteArrayOf(0x04)), 3)
        assertArrayEquals(byteArrayOf(0x03, 0x04), frame1)
    }
}
