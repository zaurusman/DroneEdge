package com.yotam.droneedge.video

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DumlFramerTest {

    private lateinit var framer: DumlFramer

    @Before fun setUp() { framer = DumlFramer() }

    private fun pkt(
        src: Int = 0x06, dst: Int = 0x07, seq: Int = 0,
        cmdSet: Int = 0x00, cmdId: Int = 0x00,
        payload: ByteArray = byteArrayOf(), needAck: Boolean = false,
    ): ByteArray = DumlFramer().buildPacket(src, dst, seq, cmdSet, cmdId, payload, needAck)

    @Test fun `returns null when fewer than 4 bytes fed`() {
        assertNull(framer.feed(byteArrayOf(0x55, 0x0D, 0x00), 3))
    }

    @Test fun `returns null for truncated packet`() {
        val p = pkt()
        assertNull(framer.feed(p, p.size / 2))
    }

    @Test fun `resyncs past leading non-SOF bytes`() {
        val garbage = byteArrayOf(0x01, 0xAB.toByte(), 0x00)
        val p = pkt(seq = 7)
        val result = framer.feed(garbage + p, garbage.size + p.size)
        assertNotNull(result)
        assertEquals(7, result!!.seq)
    }

    @Test fun `returns null and resyncs on bad CRC8`() {
        val p = pkt(seq = 1).copyOf()
        p[3] = (p[3].toInt() xor 0xFF).toByte()
        assertNull(framer.feed(p, p.size))
        // After resync, a good packet is parseable
        val good = pkt(seq = 9)
        assertNotNull(framer.feed(good, good.size))
    }

    @Test fun `returns null and resyncs on bad CRC16`() {
        val p = pkt(seq = 2).copyOf()
        p[p.size - 1] = (p[p.size - 1].toInt() xor 0xFF).toByte()
        assertNull(framer.feed(p, p.size))
        val good = pkt(seq = 10)
        assertNotNull(framer.feed(good, good.size))
    }

    @Test fun `parses complete packet with no payload`() {
        val p = pkt(src = 0x06, dst = 0x07, seq = 42, cmdSet = 0x09, cmdId = 0x09, needAck = true)
        val r = framer.feed(p, p.size)
        assertNotNull(r); r!!
        assertEquals(0x06, r.src)
        assertEquals(0x07, r.dst)
        assertEquals(42,   r.seq)
        assertEquals(0x09, r.cmdSet)
        assertEquals(0x09, r.cmdId)
        assertTrue(r.payload.isEmpty())
        assertEquals(0x80, r.attributes and 0x80)
    }

    @Test fun `parses complete packet with payload`() {
        val data = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val p = pkt(cmdSet = 0x05, cmdId = 0x0A, payload = data)
        val r = framer.feed(p, p.size)
        assertNotNull(r)
        assertArrayEquals(data, r!!.payload)
    }

    @Test fun `assembles packet split across two feed calls`() {
        val p = pkt(seq = 11)
        val half = p.size / 2
        assertNull(framer.feed(p.copyOfRange(0, half), half))
        val r = framer.feed(p.copyOfRange(half, p.size), p.size - half)
        assertNotNull(r)
        assertEquals(11, r!!.seq)
    }

    @Test fun `buildPacket round-trips through feed`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val p = DumlFramer().buildPacket(0x06, 0x07, 55, 0x09, 0x05, payload, needAck = false)
        val r = framer.feed(p, p.size)
        assertNotNull(r); r!!
        assertEquals(0x06, r.src); assertEquals(0x07, r.dst)
        assertEquals(55, r.seq);   assertEquals(0x09, r.cmdSet)
        assertEquals(0x05, r.cmdId)
        assertArrayEquals(payload, r.payload)
    }

    @Test fun `two packets in one feed — second buffered and returned on next call`() {
        val p1 = pkt(seq = 1, cmdId = 0x01)
        val p2 = pkt(seq = 2, cmdId = 0x02)
        val r1 = framer.feed(p1 + p2, p1.size + p2.size)
        assertNotNull(r1); assertEquals(1, r1!!.seq)
        val r2 = framer.feed(byteArrayOf(), 0)
        assertNotNull(r2); assertEquals(2, r2!!.seq)
    }

    @Test fun `reset discards buffered partial packet`() {
        val p = pkt(seq = 0)
        framer.feed(p, p.size / 2)  // partial
        framer.reset()
        val fresh = pkt(seq = 77)
        val r = framer.feed(fresh, fresh.size)
        assertNotNull(r)
        assertEquals(77, r!!.seq)
    }
}
