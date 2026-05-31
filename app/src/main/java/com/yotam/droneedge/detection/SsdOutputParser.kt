package com.yotam.droneedge.detection

/**
 * Parses the 4-tensor output of SSD MobileNet V1 / V2 and EfficientDet-Lite models.
 *
 * Output tensor layout (all float32):
 *  0 — locations   [1][maxDetections][4]  (ymin, xmin, ymax, xmax) normalized [0,1]
 *  1 — classes     [1][maxDetections]     class index (0-based into labels)
 *  2 — scores      [1][maxDetections]     confidence in [0, 1]
 *  3 — count       [1]                   number of valid detections
 */
// labelOffset=1 is the standard convention for TF Hub SSD models: the labelmap reserves
// index 0 for background ("???"), so the model's 0-based class output must be shifted by 1.
class SsdOutputParser(
    private val maxDetections: Int = 10,
    private val labelOffset: Int = 1,
) : DetectionOutputParser {

    override val numOutputs = 4

    @Suppress("UNCHECKED_CAST")
    override fun allocateOutputs(maxDetections: Int): Array<Any> = arrayOf(
        Array(1) { Array(maxDetections) { FloatArray(4) } }, // boxes  [1][N][4]
        Array(1) { FloatArray(maxDetections) },              // classes[1][N]
        Array(1) { FloatArray(maxDetections) },              // scores [1][N]
        FloatArray(1),                                       // count  [1]
    )

    @Suppress("UNCHECKED_CAST")
    override fun parse(
        outputs: Array<Any>,
        labels: List<String>,
        confidenceThreshold: Float,
    ): List<Detection> {
        val boxes      = (outputs[0] as Array<Array<FloatArray>>)[0]
        val classes    = (outputs[1] as Array<FloatArray>)[0]
        val scores     = (outputs[2] as Array<FloatArray>)[0]
        val count      = (outputs[3] as FloatArray)[0].toInt().coerceAtMost(maxDetections)

        return (0 until count).mapNotNull { i ->
            val score = scores[i]
            if (score < confidenceThreshold) return@mapNotNull null

            val classIdx = (classes[i].toInt() + labelOffset).coerceAtLeast(0)
            val label = labels.getOrElse(classIdx) { "class $classIdx" }
                .takeIf { it != "???" } ?: return@mapNotNull null  // skip placeholder entries

            val box = boxes[i]  // [ymin, xmin, ymax, xmax]
            Detection(
                label       = label,
                confidence  = score,
                boundingBox = BoundingBox(
                    left   = box[1].coerceIn(0f, 1f),
                    top    = box[0].coerceIn(0f, 1f),
                    right  = box[3].coerceIn(0f, 1f),
                    bottom = box[2].coerceIn(0f, 1f),
                )
            )
        }
    }
}
