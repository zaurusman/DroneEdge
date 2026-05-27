package com.yotam.droneedge.detection

import com.yotam.droneedge.video.VideoFrame

/**
 * Replaceable object detector.
 *
 * Implementations: FakeDetector, TfliteDetector.
 * All implementations must be safe to call from a background thread.
 */
interface Detector {
    /** Run inference on [frame] and return zero or more detections. */
    suspend fun detect(frame: VideoFrame): List<Detection>
}
