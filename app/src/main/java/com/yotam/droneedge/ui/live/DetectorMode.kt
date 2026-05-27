package com.yotam.droneedge.ui.live

enum class DetectorMode {
    /** Animated synthetic bounding boxes — no model required. */
    FAKE,
    /** On-device TensorFlow Lite inference from assets/detect.tflite. */
    TFLITE,
}
