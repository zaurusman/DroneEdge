package com.droneedge.app.detection

/**
 * Converts the raw output tensors of a TFLite detection model into [Detection] objects.
 *
 * Different models produce different numbers of output tensors with different layouts.
 * Implementing this interface decouples [TfliteDetector] from any specific model format.
 *
 * Implementations: [SsdOutputParser] for SSD MobileNet / EfficientDet-Lite family.
 */
interface DetectionOutputParser {
    /** Number of output tensors the model produces. */
    val numOutputs: Int

    /**
     * Allocate pre-sized output containers that will be passed to
     * [org.tensorflow.lite.Interpreter.runForMultipleInputsOutputs].
     */
    fun allocateOutputs(maxDetections: Int): Array<Any>

    /**
     * Populate [Detection] list from the filled output containers.
     *
     * @param outputs           The same array returned by [allocateOutputs], now filled.
     * @param labels            Class-name list loaded from the label file.
     * @param confidenceThreshold Minimum score to include a detection.
     */
    fun parse(
        outputs: Array<Any>,
        labels: List<String>,
        confidenceThreshold: Float,
    ): List<Detection>
}
