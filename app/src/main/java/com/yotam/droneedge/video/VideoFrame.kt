package com.yotam.droneedge.video

/**
 * A single frame produced by a VideoSource.
 *
 * Phase 1: carries only metadata (index, timestamp, dimensions).
 * Phase 2 will add pixel data (Bitmap or ByteArray) when real video decoding is added.
 */
data class VideoFrame(
    val index: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
)
