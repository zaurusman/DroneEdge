package com.droneedge.app.video

import android.graphics.Bitmap

/**
 * A single frame produced by a VideoSource.
 *
 * [bitmap] is null for FakeVideoSource (no real pixel data).
 * FileReplayVideoSource fills it via MediaMetadataRetriever.
 * Phase 5+ will replace it with a proper decoded surface buffer.
 */
data class VideoFrame(
    val index: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap? = null,
)
