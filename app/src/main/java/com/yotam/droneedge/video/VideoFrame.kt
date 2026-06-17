package com.droneedge.app.video

import android.graphics.Bitmap

/**
 * A single frame produced by a VideoSource.
 *
 * [bitmap] is null for FakeVideoSource (no real pixel data).
 * FileMediaCodecVideoSource and the DJI sources fill it via PixelCopy of the rendered surface.
 */
data class VideoFrame(
    val index: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap? = null,
)
