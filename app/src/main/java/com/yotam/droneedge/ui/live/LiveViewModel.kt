package com.yotam.droneedge.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.detection.FakeDetector
import com.yotam.droneedge.video.FakeVideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveViewModel : ViewModel() {

    // Video and detection pipeline (replaceable in later phases)
    private val videoSource = FakeVideoSource()
    private val detector = FakeDetector()

    // ── Session state ──────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // ── Detection results ──────────────────────────────────────────────────────
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

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (_sessionState.value != SessionState.IDLE) return
        _sessionState.value = SessionState.RUNNING
        lastPreviewFrameMs = 0L
        lastInferenceMs = 0L
        videoSource.start()

        pipelineJob = viewModelScope.launch {
            videoSource.frames.collect { frame ->
                // Preview FPS (exponential moving average)
                val nowPreview = System.currentTimeMillis()
                if (lastPreviewFrameMs > 0L) {
                    val interval = nowPreview - lastPreviewFrameMs
                    if (interval > 0) {
                        val instant = 1000f / interval
                        _previewFps.value = _previewFps.value * 0.85f + instant * 0.15f
                    }
                }
                lastPreviewFrameMs = nowPreview

                // Run detection off the main thread
                val results = withContext(Dispatchers.Default) {
                    detector.detect(frame)
                }
                _detections.value = results

                // Inference FPS
                val nowInfer = System.currentTimeMillis()
                if (lastInferenceMs > 0L) {
                    val interval = nowInfer - lastInferenceMs
                    if (interval > 0) {
                        val instant = 1000f / interval
                        _inferenceFps.value = _inferenceFps.value * 0.85f + instant * 0.15f
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
