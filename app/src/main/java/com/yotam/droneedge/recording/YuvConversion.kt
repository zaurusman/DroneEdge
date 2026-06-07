package com.droneedge.app.recording

import android.graphics.Bitmap
import android.media.Image

// Writes an NV12 byte array into a MediaCodec input Image, handling both
// I420-planar (pixelStride==1) and NV12-semi-planar (pixelStride==2) layouts.
fun writeNv12ToImage(nv12: ByteArray, image: Image, width: Int, height: Int) {
    val yPlane = image.planes[0]
    val yBuf = yPlane.buffer
    val yRowStride = yPlane.rowStride
    for (row in 0 until height) {
        yBuf.position(row * yRowStride)
        yBuf.put(nv12, row * width, width)
    }

    val uvSrcOffset = width * height
    val uvPlane  = image.planes[1]
    val uvPixelStride = uvPlane.pixelStride
    val uvRowStride   = uvPlane.rowStride

    if (uvPixelStride == 1) {
        // I420 planar: separate U and V buffers
        val uBuf = uvPlane.buffer
        val vBuf = image.planes[2].buffer
        val vRowStride = image.planes[2].rowStride
        for (row in 0 until height / 2) {
            uBuf.position(row * uvRowStride)
            vBuf.position(row * vRowStride)
            for (col in 0 until width / 2) {
                uBuf.put(nv12[uvSrcOffset + row * width + col * 2])
                vBuf.put(nv12[uvSrcOffset + row * width + col * 2 + 1])
            }
        }
    } else {
        // NV12 semi-planar: interleaved UV already in planes[1]
        val uvBuf = uvPlane.buffer
        for (row in 0 until height / 2) {
            uvBuf.position(row * uvRowStride)
            uvBuf.put(nv12, uvSrcOffset + row * width, width)
        }
    }
}

fun bitmapToI420(bitmap: Bitmap): ByteArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return i420FromPixels(pixels, bitmap.width, bitmap.height)
}

// I420 (YUV420 planar): Y plane, then all-U plane, then all-V plane.
// COLOR_FormatYUV420Flexible + getInputBuffer() on Android's software AVC encoder uses this layout.
fun i420FromPixels(pixels: IntArray, width: Int, height: Int): ByteArray {
    val out = ByteArray(width * height * 3 / 2)

    for (i in pixels.indices) {
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8)  and 0xFF
        val b =  pixels[i]         and 0xFF
        out[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
    }

    val uOffset = width * height
    val vOffset = uOffset + (width / 2) * (height / 2)
    var uvIndex = 0
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val p = pixels[row * 2 * width + col * 2]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            out[uOffset + uvIndex] = (((-38 * r -  74 * g + 112 * b + 128) shr 8) + 128).toByte()
            out[vOffset + uvIndex] = (((112 * r -  94 * g -  18 * b + 128) shr 8) + 128).toByte()
            uvIndex++
        }
    }
    return out
}

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
