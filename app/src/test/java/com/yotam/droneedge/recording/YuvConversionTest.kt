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

    @Test
    fun yuv420BlackFrameDecodesCorrectly() {
        // 2×2 all-black frame: Y=16, U=128, V=128 (BT.601 black level)
        // yRowStride=2, uvRowStride=1, uvPixelStride=1 (I420 planar)
        val yBytes = byteArrayOf(16, 16, 16, 16)
        val uBytes = byteArrayOf(128.toByte())
        val vBytes = byteArrayOf(128.toByte())
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        pixels.forEach { pixel ->
            assertEquals("alpha", 0xFF, (pixel ushr 24) and 0xFF)
            assertEquals("R", 0, (pixel shr 16) and 0xFF)
            assertEquals("G", 0, (pixel shr 8) and 0xFF)
            assertEquals("B", 0, pixel and 0xFF)
        }
    }

    @Test
    fun yuv420WhiteFrameDecodesCorrectly() {
        // 2×2 all-white frame: Y=235, U=128, V=128
        val yBytes = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte())
        val uBytes = byteArrayOf(128.toByte())
        val vBytes = byteArrayOf(128.toByte())
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        pixels.forEach { pixel ->
            assertEquals("alpha", 0xFF, (pixel ushr 24) and 0xFF)
            assertEquals("R", 255, (pixel shr 16) and 0xFF)
            assertEquals("G", 255, (pixel shr 8) and 0xFF)
            assertEquals("B", 255, pixel and 0xFF)
        }
    }

    @Test
    fun yuv420OutputSizeIsWidthTimesHeight() {
        val yBytes = ByteArray(4) { 16 }
        val uBytes = ByteArray(1) { 128.toByte() }
        val vBytes = ByteArray(1) { 128.toByte() }
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 1, 1, 2, 2)
        assertEquals(4, pixels.size)
    }
}
