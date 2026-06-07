package com.droneedge.app.recording

import android.content.Context
import com.droneedge.app.detection.Detection
import com.droneedge.app.video.VideoFrame

class FakeSessionRecorder : SessionRecorder {
    var startCalled = false
    var stopCalled = false
    val framesReceived = mutableListOf<Pair<VideoFrame, List<Detection>>>()

    override suspend fun start(width: Int, height: Int, fps: Int, context: Context) {
        startCalled = true
    }

    override suspend fun onFrame(frame: VideoFrame, detections: List<Detection>) {
        framesReceived.add(frame to detections)
    }

    override suspend fun stop(): RecordingResult {
        stopCalled = true
        return RecordingResult(
            videoUri   = null,
            jsonUri    = null,
            sessionId  = "session_fake",
            frameCount = framesReceived.size,
            durationMs = 0L,
        )
    }
}
