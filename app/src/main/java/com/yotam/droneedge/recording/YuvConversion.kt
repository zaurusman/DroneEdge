package com.droneedge.app.recording

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

// Writes an NV12 byte array into a MediaCodec input Image, handling both
// I420-planar (pixelStride==1) and NV12-semi-planar (pixelStride==2) layouts.
fun writeNv12ToImage(nv12: ByteArray, image: Image, width: Int, height: Int) {
    val y = image.planes[0]
    val u = image.planes[1]
    val v = image.planes[2]
    writeNv12Planes(
        nv12, width, height,
        y.buffer, y.rowStride,
        u.buffer, u.rowStride, u.pixelStride,
        v.buffer, v.rowStride, v.pixelStride,
    )
}

/**
 * Copies an NV12 byte array (Y plane, then interleaved U,V) into the destination
 * plane buffers of a MediaCodec input Image, honouring each plane's rowStride and
 * pixelStride.
 *
 * Chroma is written with **absolute** puts (one sample at a time) into planes[1]
 * for U/Cb and planes[2] for V/Cr. This is correct for both:
 *   - I420 planar     (pixelStride 1, separate U and V buffers), and
 *   - NV12 semi-planar (pixelStride 2, U and V are two views of one interleaved buffer).
 *
 * Crucially it never runs past a plane buffer's limit. On semi-planar layouts
 * planes[1]'s limit excludes the final V byte, so a bulk `put(width)` of the last
 * chroma row overflows by one byte → BufferOverflowException. Per-sample absolute
 * puts (U via planes[1], V via planes[2]) stay inside each plane's own limit.
 *
 * Plane parameters mirror android.media.Image.Plane so the logic stays free of an
 * Android dependency and is unit-testable.
 */
fun writeNv12Planes(
    nv12: ByteArray,
    width: Int, height: Int,
    yBuf: ByteBuffer, yRowStride: Int,
    uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
    vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
) {
    // Y plane — one byte per pixel. The Y buffer's limit always covers
    // rowStride*height, so a bulk row copy is safe and fast.
    for (row in 0 until height) {
        yBuf.position(row * yRowStride)
        yBuf.put(nv12, row * width, width)
    }

    // Chroma — nv12 stores interleaved U,V (NV12) right after the Y plane.
    val uvSrc = width * height
    for (row in 0 until height / 2) {
        val src = uvSrc + row * width
        var uDst = row * uRowStride
        var vDst = row * vRowStride
        for (col in 0 until width / 2) {
            uBuf.put(uDst, nv12[src + col * 2])
            vBuf.put(vDst, nv12[src + col * 2 + 1])
            uDst += uPixelStride
            vDst += vPixelStride
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

/**
 * Converts YUV_420_888 image plane data to packed ARGB pixels (alpha=255).
 *
 * Handles both I420 planar (uvPixelStride=1) and NV12 semi-planar (uvPixelStride=2) layouts.
 * BT.601 limited-range coefficients.
 *
 * Parameters match android.media.Image.Plane values so the caller can extract bytes
 * and call this without an Android dependency in the function itself.
 */
fun yuv420ToArgbPixels(
    yBytes: ByteArray, yRowStride: Int,
    uBytes: ByteArray, vBytes: ByteArray,
    uvRowStride: Int, uvPixelStride: Int,
    width: Int, height: Int,
): IntArray {
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        for (col in 0 until width) {
            val y = (yBytes[row * yRowStride + col].toInt() and 0xFF) - 16
            val uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
            val u = (uBytes[uvIdx].toInt() and 0xFF) - 128
            val v = (vBytes[uvIdx].toInt() and 0xFF) - 128
            val r = ((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return pixels
}
