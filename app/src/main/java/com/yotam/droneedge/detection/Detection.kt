package com.yotam.droneedge.detection

import android.graphics.RectF

/**
 * A single object detection result for one frame.
 *
 * @param label      Human-readable class name (e.g. "person", "drone").
 * @param confidence Score in [0, 1].
 * @param boundingBox Normalized coordinates in [0, 1] relative to the frame
 *                    dimensions (left, top, right, bottom).
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
)
