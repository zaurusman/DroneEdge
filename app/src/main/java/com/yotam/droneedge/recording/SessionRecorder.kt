package com.droneedge.app.recording

import android.content.Context
import android.net.Uri
import com.droneedge.app.detection.Detection
import com.droneedge.app.video.VideoFrame

data class RecordingResult(
    val videoUri:   Uri?,
    val jsonUri:    Uri?,
    val sessionId:  String,
    val frameCount: Int,
    val durationMs: Long,
)

interface SessionRecorder {
    suspend fun start(width: Int, height: Int, fps: Int, context: Context)
    suspend fun onFrame(frame: VideoFrame, detections: List<Detection>)
    suspend fun stop(): RecordingResult
}
