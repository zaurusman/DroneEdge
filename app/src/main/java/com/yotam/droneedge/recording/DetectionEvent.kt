package com.yotam.droneedge.recording

import com.yotam.droneedge.detection.Detection

data class DetectionEvent(
    val frameIndex: Long,
    val timestampMs: Long,
    val detections: List<Detection>,
) {
    fun toJson(): String = buildString {
        append("""{"frameIndex":$frameIndex,"timestampMs":$timestampMs,"detections":[""")
        detections.forEachIndexed { i, d ->
            if (i > 0) append(",")
            append("""{"label":"${d.label}",""")
            append(""""confidence":${"%.4f".format(d.confidence)},""")
            append(""""left":${"%.4f".format(d.boundingBox.left)},""")
            append(""""top":${"%.4f".format(d.boundingBox.top)},""")
            append(""""right":${"%.4f".format(d.boundingBox.right)},""")
            append(""""bottom":${"%.4f".format(d.boundingBox.bottom)}}""")
        }
        append("]}")
    }
}
