package com.droneedge.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264NalParserTest {

    @Test fun nalType_threeByteStartCode() {
        // 00 00 01 | 0x65 -> type 5 (IDR): 0x65 & 0x1F = 5
        val nal = byteArrayOf(0, 0, 1, 0x65.toByte(), 0x11, 0x22)
        assertEquals(5, H264NalParser.nalType(nal))
    }

    @Test fun nalType_fourByteStartCode() {
        // 00 00 00 01 | 0x67 -> type 7 (SPS): 0x67 & 0x1F = 7
        val nal = byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42)
        assertEquals(7, H264NalParser.nalType(nal))
    }

    @Test fun nalType_pps() {
        val nal = byteArrayOf(0, 0, 1, 0x68.toByte()) // 0x68 & 0x1F = 8
        assertEquals(8, H264NalParser.nalType(nal))
    }

    @Test fun nalType_returnsMinusOneWhenNoPayload() {
        assertEquals(-1, H264NalParser.nalType(byteArrayOf(0, 0, 1)))
    }

    @Test fun constantsMatchH264() {
        assertEquals(5, H264NalParser.NAL_IDR)
        assertEquals(7, H264NalParser.NAL_SPS)
        assertEquals(8, H264NalParser.NAL_PPS)
    }
}
