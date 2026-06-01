package com.yotam.droneedge.recording

import android.content.Context
import android.net.Uri
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame

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
            videoUri   = Uri.EMPTY,
            jsonUri    = Uri.EMPTY,
            sessionId  = "session_fake",
            frameCount = framesReceived.size,
            durationMs = 0L,
        )
    }
}
