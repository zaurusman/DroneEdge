package com.yotam.droneedge.detection

/**
 * Normalized bounding box in [0, 1] relative to frame dimensions.
 * Pure Kotlin — no Android dependencies, fully testable on the JVM.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * A single object detection result for one frame.
 *
 * @param label       Human-readable class name (e.g. "person", "drone").
 * @param confidence  Score in [0, 1].
 * @param boundingBox Normalized coordinates in [0, 1] relative to frame dimensions.
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: BoundingBox,
)
