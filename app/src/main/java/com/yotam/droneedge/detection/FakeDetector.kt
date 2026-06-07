package com.droneedge.app.detection

import com.droneedge.app.video.VideoFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * Returns animated synthetic detections derived from the frame timestamp.
 * Boxes move via sin/cos so the overlay is visibly animated without real inference.
 */
class FakeDetector : Detector {

    override suspend fun detect(frame: VideoFrame): List<Detection> {
        val t = frame.timestampMs / 1000.0 // seconds

        val personLeft = (0.1f + 0.3f * sin(t).toFloat()).coerceIn(0f, 0.75f)
        val personTop  = (0.2f + 0.2f * cos(t * 0.7).toFloat()).coerceIn(0f, 0.65f)

        val droneLeft  = (0.5f + 0.25f * cos(t * 1.3).toFloat()).coerceIn(0f, 0.75f)
        val droneTop   = (0.1f + 0.3f  * sin(t * 0.9).toFloat()).coerceIn(0f, 0.78f)

        return listOf(
            Detection(
                label       = "person",
                confidence  = 0.91f,
                boundingBox = BoundingBox(
                    left   = personLeft,
                    top    = personTop,
                    right  = (personLeft + 0.22f).coerceAtMost(1f),
                    bottom = (personTop  + 0.32f).coerceAtMost(1f),
                )
            ),
            Detection(
                label       = "drone",
                confidence  = 0.76f,
                boundingBox = BoundingBox(
                    left   = droneLeft,
                    top    = droneTop,
                    right  = (droneLeft + 0.18f).coerceAtMost(1f),
                    bottom = (droneTop  + 0.14f).coerceAtMost(1f),
                )
            ),
        )
    }
}
