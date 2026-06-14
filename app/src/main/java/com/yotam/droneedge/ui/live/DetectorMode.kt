package com.droneedge.app.ui.live

import android.content.res.AssetManager

enum class DetectorMode {
    NO_MODEL,
    FAKE,
    TFLITE,
    NORTH,
    NORTH_F32,
    NORTH_GPU,
    // To add a new model:
    // 1. Add an enum value here
    // 2. Add a ModelDescriptor entry in ModelRegistry.all below
    // 3. Drop the .tflite file into app/src/main/assets/
}

data class ModelDescriptor(
    val mode:        DetectorMode,
    val displayName: String,
    val shortLabel:  String,
    val description: String,
    val assetFile:   String?,   // null = always available (no bundled asset required)
)

fun ModelDescriptor.isAvailable(assets: AssetManager): Boolean {
    val file = assetFile ?: return true
    return runCatching { assets.open(file).close() }.isSuccess
}

object ModelRegistry {
    val all: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            mode        = DetectorMode.NO_MODEL,
            displayName = "No Model",
            shortLabel  = "Off",
            description = "Video preview only — detection is disabled. No bounding boxes or detection events.",
            assetFile   = null,
        ),
        ModelDescriptor(
            mode        = DetectorMode.FAKE,
            displayName = "Fake Detector",
            shortLabel  = "Fake",
            description = "Generates random bounding boxes. Use for UI testing without a model file.",
            assetFile   = null,
        ),
        ModelDescriptor(
            mode        = DetectorMode.TFLITE,
            displayName = "TFLite — SSD MobileNet",
            shortLabel  = "TFLite",
            description = "On-device object detection. Runs inference off the main thread.",
            assetFile   = "detect.tflite",
        ),
        ModelDescriptor(
            mode        = DetectorMode.NORTH,
            displayName = "North — FP16",
            shortLabel  = "North",
            description = "Custom model, FP16 quantized weights (north_20260419.tflite).",
            assetFile   = "north_20260419.tflite",
        ),
        ModelDescriptor(
            mode        = DetectorMode.NORTH_F32,
            displayName = "North — Float32",
            shortLabel  = "F32",
            description = "Same model, all weights in full float32. No DEQUANTIZE ops — GPU delegate compatible.",
            assetFile   = "north_float32.tflite",
        ),
        ModelDescriptor(
            mode        = DetectorMode.NORTH_GPU,
            displayName = "North — GPU (FP32, no postproc)",
            shortLabel  = "GPU",
            description = "North FP16 model converted to FP32 with DEQUANTIZE ops constant-folded. No INT64 ops. Best chance of GPU delegate success.",
            assetFile   = "north_gpu.tflite",
        ),
    )
}
