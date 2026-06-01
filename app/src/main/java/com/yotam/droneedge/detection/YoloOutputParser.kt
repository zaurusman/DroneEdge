package com.yotam.droneedge.detection

/**
 * Parses the single-tensor output of YOLOv8 TFLite models.
 *
 * Output tensor layout: [1, (4 + numClasses), numAnchors]
 *   rows 0–3 : x_center, y_center, width, height  (normalized 0–1)
 *   rows 4.. : class confidence scores
 *
 * Applies greedy NMS before returning results.
 */
class YoloOutputParser(
    private val numAnchors: Int,
    private val numValues: Int,
    private val nmsIouThreshold: Float = 0.45f,
    private val maxDetections: Int = 30,
) : DetectionOutputParser {

    override val numOutputs = 1

    override fun allocateOutputs(maxDetections: Int): Array<Any> =
        arrayOf(Array(1) { Array(numValues) { FloatArray(numAnchors) } })

    @Suppress("UNCHECKED_CAST")
    override fun parse(
        outputs: Array<Any>,
        labels: List<String>,
        confidenceThreshold: Float,
    ): List<Detection> {
        val raw = (outputs[0] as Array<Array<FloatArray>>)[0] // [numValues][numAnchors]
        val numClasses = numValues - 4

        val candidates = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx = raw[0][i]
            val cy = raw[1][i]
            val w  = raw[2][i]
            val h  = raw[3][i]

            var bestClass = 0
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = raw[4 + c][i]
                if (score > bestScore) { bestScore = score; bestClass = c }
            }

            if (bestScore < confidenceThreshold) continue

            candidates.add(Detection(
                label      = labels.getOrElse(bestClass) { "class $bestClass" },
                confidence = bestScore,
                boundingBox = BoundingBox(
                    left   = (cx - w / 2f).coerceIn(0f, 1f),
                    top    = (cy - h / 2f).coerceIn(0f, 1f),
                    right  = (cx + w / 2f).coerceIn(0f, 1f),
                    bottom = (cy + h / 2f).coerceIn(0f, 1f),
                ),
            ))
        }

        candidates.sortByDescending { it.confidence }
        return nms(candidates).take(maxDetections)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        val suppressed = BooleanArray(detections.size)
        for (i in detections.indices) {
            if (suppressed[i]) continue
            result.add(detections[i])
            for (j in i + 1 until detections.size) {
                if (!suppressed[j] && iou(detections[i].boundingBox, detections[j].boundingBox) > nmsIouThreshold)
                    suppressed[j] = true
            }
        }
        return result
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (aArea + bArea - interArea)
    }
}
