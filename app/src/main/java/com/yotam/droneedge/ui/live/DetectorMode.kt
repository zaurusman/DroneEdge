package com.yotam.droneedge.ui.live

import android.content.res.AssetManager

enum class DetectorMode {
    FAKE,
    TFLITE,
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
    )
}
