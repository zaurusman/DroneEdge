package com.droneedge.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvConversionTest {

    // BT.601 formulas:
    //   Y  = ( 66R + 129G +  25B + 128) >> 8 + 16
    //   Cb = (-38R -  74G + 112B + 128) >> 8 + 128
    //   Cr = (112R -  94G -  18B + 128) >> 8 + 128

    @Test
    fun blackPixelProducesCorrectYuv() {
        // 2×2 all-black ARGB_8888 pixels (0xFF000000)
        val black = 0xFF000000.toInt()
        val pixels = intArrayOf(black, black, black, black)
        val nv12 = nv12FromPixels(pixels, 2, 2)

        // Y = 16 for all 4 pixels
        assertEquals(16, nv12[0].toInt() and 0xFF)
        assertEquals(16, nv12[1].toInt() and 0xFF)
        assertEquals(16, nv12[2].toInt() and 0xFF)
        assertEquals(16, nv12[3].toInt() and 0xFF)
        // UV plane: Cb = 128, Cr = 128
        assertEquals(128, nv12[4].toInt() and 0xFF) // Cb
        assertEquals(128, nv12[5].toInt() and 0xFF) // Cr
    }

    @Test
    fun whitePixelProducesCorrectYuv() {
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(white, white, white, white)
        val nv12 = nv12FromPixels(pixels, 2, 2)

        // Y = 235 for white
        assertEquals(235, nv12[0].toInt() and 0xFF)
        // Cb = 128, Cr = 128 for achromatic
        assertEquals(128, nv12[4].toInt() and 0xFF)
        assertEquals(128, nv12[5].toInt() and 0xFF)
    }

    @Test
    fun outputSizeIsCorrect() {
        val pixels = IntArray(4 * 2) { 0xFF000000.toInt() }
        val nv12 = nv12FromPixels(pixels, 4, 2)
        // NV12 total size = width * height * 3 / 2
        assertEquals(4 * 2 * 3 / 2, nv12.size)
    }
}
