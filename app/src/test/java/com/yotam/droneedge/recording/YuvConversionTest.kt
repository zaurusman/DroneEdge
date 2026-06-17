package com.droneedge.app.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

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

    @Test
    fun yuv420NV12SemiPlanarPathDecodesCorrectly() {
        // 2×2 frame using NV12 semi-planar layout (uvPixelStride=2).
        // In NV12, uBytes and vBytes point into the same interleaved buffer but offset by 1.
        // uvRowStride=2, uvPixelStride=2: uBytes=[U0, V0], vBytes=[V0, U0] (second byte of same pair).
        // Use black (Y=16, U=128, V=128) — chrominance doesn't change for achromatic colours.
        val yBytes = byteArrayOf(16, 16, 16, 16)
        // NV12 interleaved UV: [U0=128, V0=128]
        val uBytes = byteArrayOf(128.toByte(), 128.toByte())   // U at [0], [2], ...
        val vBytes = byteArrayOf(128.toByte(), 128.toByte())   // V at [1], [3], ... (overlapping)
        // uvRowStride=2, uvPixelStride=2
        val pixels = yuv420ToArgbPixels(yBytes, 2, uBytes, vBytes, 2, 2, 2, 2)
        pixels.forEach { pixel ->
            assertEquals("alpha NV12", 0xFF, (pixel ushr 24) and 0xFF)
            assertEquals("R NV12", 0, (pixel shr 16) and 0xFF)
            assertEquals("G NV12", 0, (pixel shr 8) and 0xFF)
            assertEquals("B NV12", 0, pixel and 0xFF)
        }
    }

    // ── writeNv12Planes: encoder input feed (recording-color-corruption fix) ──────
    //
    // A 4×4 frame. NV12 = 16 Y bytes + 8 interleaved chroma bytes (2×2 chroma).
    // Chroma source layout (interleaved U,V), row stride = width = 4:
    //   row0: U0=10 V0=11 U1=12 V1=13
    //   row1: U0=14 V0=15 U1=16 V1=17
    private val width = 4
    private val height = 4
    private val nv12 = ByteArray(24).also { buf ->
        for (i in 0 until 16) buf[i] = (i + 100).toByte()           // Y plane, distinct values
        val chroma = intArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
        for (i in chroma.indices) buf[16 + i] = chroma[i].toByte()
    }

    @Test
    fun writeNv12SemiPlanarLandsSamplesAtCorrectStride() {
        // Semi-planar: U (planes[1]) and V (planes[2]) are two views of ONE 8-byte
        // chroma region, V offset by 1. rowStride = 4, pixelStride = 2.
        // planes[1]'s limit excludes the final V byte (index 7) — exactly the real
        // Android layout that made the old bulk put overflow.
        val region = ByteArray(8)
        val uBuf = ByteBuffer.wrap(region, 0, 7).slice()   // U view: limit 7 (index 7 invalid)
        val vBuf = ByteBuffer.wrap(region, 1, 7).slice()   // V view: maps to region[1..7]
        val yBuf = ByteBuffer.allocate(16)

        writeNv12Planes(
            nv12, width, height,
            yBuf, /*yRowStride*/ 4,
            uBuf, /*uRowStride*/ 4, /*uPixelStride*/ 2,
            vBuf, /*vRowStride*/ 4, /*vPixelStride*/ 2,
        )

        // U at even region indices, V at odd — i.e. the original interleaved order.
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17), region)
    }

    @Test
    fun writeNv12PlanarLandsUAndVInSeparateBuffers() {
        // I420 planar: separate U and V buffers, pixelStride 1, rowStride 2 (= width/2).
        val uBuf = ByteBuffer.allocate(4)
        val vBuf = ByteBuffer.allocate(4)
        val yBuf = ByteBuffer.allocate(16)

        writeNv12Planes(
            nv12, width, height,
            yBuf, 4,
            uBuf, /*uRowStride*/ 2, /*uPixelStride*/ 1,
            vBuf, /*vRowStride*/ 2, /*vPixelStride*/ 1,
        )

        // U = the even chroma bytes, V = the odd ones.
        assertArrayEquals(byteArrayOf(10, 12, 14, 16), uBuf.array())
        assertArrayEquals(byteArrayOf(11, 13, 15, 17), vBuf.array())
    }

    @Test
    fun oldBulkChromaPutOverflowsSemiPlanarLastRow() {
        // Reproduces the regression: the previous semi-planar path did a bulk
        // `put(nv12, offset, width)` per chroma row. On the last row that runs one
        // byte past planes[1]'s limit → BufferOverflowException (null message →
        // surfaced as "Video source error").
        val region = ByteArray(8)
        val uvBuf = ByteBuffer.wrap(region, 0, 7).slice()   // limit 7, as Android reports
        assertThrows(BufferOverflowException::class.java) {
            for (row in 0 until height / 2) {
                uvBuf.position(row * 4)                       // uvRowStride = 4
                uvBuf.put(nv12, 16 + row * width, width)     // bulk put of a full row
            }
        }
    }
}
