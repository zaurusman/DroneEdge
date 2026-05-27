package com.yotam.droneedge.ui.live

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.detection.Detector
import com.yotam.droneedge.detection.FakeDetector
import com.yotam.droneedge.video.FakeVideoSource
import com.yotam.droneedge.video.FileReplayVideoSource
import com.yotam.droneedge.video.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveViewModel : ViewModel() {

    // ── Pipeline (swappable while IDLE) ──────────────────────────────────────
    private var videoSource: VideoSource = FakeVideoSource()
    private val detector: Detector = FakeDetector()

    // ── Video URI (null = fake source) ────────────────────────────────────────
    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    // ── Session state ─────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // ── Detection results ─────────────────────────────────────────────────────
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    // ── FPS ───────────────────────────────────────────────────────────────────
    private val _previewFps = MutableStateFlow(0f)
    val previewFps: StateFlow<Float> = _previewFps.asStateFlow()

    private val _inferenceFps = MutableStateFlow(0f)
    val inferenceFps: StateFlow<Float> = _inferenceFps.asStateFlow()

    private var lastPreviewFrameMs = 0L
    private var lastInferenceMs = 0L
    private var pipelineJob: Job? = null

    // ── Source selection (only while IDLE) ────────────────────────────────────

    /** Switch to a local MP4 file as the video source. */
    fun useFileSource(uri: Uri, context: Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FileReplayVideoSource(uri, context.applicationContext)
        _videoUri.value = uri
    }

    /** Switch back to the synthetic fake source. */
    fun useFakeSource() {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FakeVideoSource()
        _videoUri.value = null
    }

    // ── Session control ───────────────────────────────────────────────────────

    fun start() {
        if (_sessionState.value != SessionState.IDLE) return
        _sessionState.value = SessionState.RUNNING
        lastPreviewFrameMs = 0L
        lastInferenceMs = 0L
        videoSource.start()

        pipelineJob = viewModelScope.launch {
            videoSource.frames.collect { frame ->
                // Preview FPS — exponential moving average
                val nowPreview = System.currentTimeMillis()
                if (lastPreviewFrameMs > 0L) {
                    val interval = nowPreview - lastPreviewFrameMs
                    if (interval > 0) {
                        _previewFps.value = _previewFps.value * 0.85f + (1000f / interval) * 0.15f
                    }
                }
                lastPreviewFrameMs = nowPreview

                // Detect off the main thread
                val results = withContext(Dispatchers.Default) { detector.detect(frame) }
                _detections.value = results

                // Inference FPS
                val nowInfer = System.currentTimeMillis()
                if (lastInferenceMs > 0L) {
                    val interval = nowInfer - lastInferenceMs
                    if (interval > 0) {
                        _inferenceFps.value = _inferenceFps.value * 0.85f + (1000f / interval) * 0.15f
                    }
                }
                lastInferenceMs = nowInfer
            }
        }
    }

    fun stop() {
        if (_sessionState.value != SessionState.RUNNING) return
        _sessionState.value = SessionState.STOPPING
        videoSource.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        _detections.value = emptyList()
        _previewFps.value = 0f
        _inferenceFps.value = 0f
        _sessionState.value = SessionState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
        videoSource.stop()
        pipelineJob?.cancel()
    }
}
