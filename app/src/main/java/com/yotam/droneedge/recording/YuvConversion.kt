package com.yotam.droneedge.recording

import android.graphics.Bitmap

fun bitmapToNv12(bitmap: Bitmap): ByteArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return nv12FromPixels(pixels, bitmap.width, bitmap.height)
}

fun nv12FromPixels(pixels: IntArray, width: Int, height: Int): ByteArray {
    val nv12 = ByteArray(width * height * 3 / 2)

    // Y plane — one byte per pixel
    for (i in pixels.indices) {
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8)  and 0xFF
        val b =  pixels[i]         and 0xFF
        nv12[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
    }

    // UV plane — NV12: interleaved Cb then Cr, 2×2 subsampled
    val uvOffset = width * height
    var uvIndex = 0
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val p = pixels[row * 2 * width + col * 2]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            nv12[uvOffset + uvIndex++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            nv12[uvOffset + uvIndex++] = (((112 * r - 94 * g -  18 * b + 128) shr 8) + 128).toByte()
        }
    }
    return nv12
}
