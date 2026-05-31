package com.yotam.droneedge.recording

import android.content.Context
import android.net.Uri
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.video.VideoFrame

data class RecordingResult(
    val videoUri: Uri,
    val jsonUri: Uri,
    val frameCount: Int,
    val durationMs: Long,
)

interface SessionRecorder {
    suspend fun start(width: Int, height: Int, fps: Int, context: Context)
    suspend fun onFrame(frame: VideoFrame, detections: List<Detection>)
    suspend fun stop(): RecordingResult
}
