package com.yotam.droneedge.ui.live

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.detection.Detector
import com.yotam.droneedge.detection.FakeDetector
import com.yotam.droneedge.detection.TfliteDetector
import com.yotam.droneedge.recording.RecordingResult
import com.yotam.droneedge.recording.SessionRecorder
import com.yotam.droneedge.recording.VideoSessionRecorder
import com.yotam.droneedge.video.FakeVideoSource
import com.yotam.droneedge.video.FileReplayVideoSource
import com.yotam.droneedge.video.VideoFrame
import com.yotam.droneedge.video.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun canArm(sessionState: SessionState, recordingState: RecordingState): Boolean =
    sessionState == SessionState.RUNNING && recordingState == RecordingState.IDLE

internal fun canDisarm(recordingState: RecordingState): Boolean =
    recordingState == RecordingState.ARMED

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    // ── Pipeline (swappable while IDLE) ──────────────────────────────────────
    private var videoSource: VideoSource = FakeVideoSource()
    private var detector: Detector = FakeDetector()
    private var tfliteDetector: TfliteDetector? = null

    // ── Recorder (factory injectable for tests) ───────────────────────────────
    internal var recorderFactory: () -> SessionRecorder = { VideoSessionRecorder() }
    private var recorder: SessionRecorder? = null

    // ── Video URI (null = fake source) ────────────────────────────────────────
    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    // ── Detector mode ─────────────────────────────────────────────────────────
    private val _detectorMode = MutableStateFlow(DetectorMode.FAKE)
    val detectorMode: StateFlow<DetectorMode> = _detectorMode.asStateFlow()

    // ── Error message (null = no error) ───────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Session state ─────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // ── Recording state ───────────────────────────────────────────────────────
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    // ── Last completed recording (drives snackbar) ────────────────────────────
    private val _lastRecording = MutableStateFlow<RecordingResult?>(null)
    val lastRecording: StateFlow<RecordingResult?> = _lastRecording.asStateFlow()

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

    fun useFileSource(uri: Uri, context: android.content.Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FileReplayVideoSource(uri, context.applicationContext)
        _videoUri.value = uri
    }

    fun useFakeSource() {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource = FakeVideoSource()
        _videoUri.value = null
    }

    // ── Detector selection (only while IDLE) ──────────────────────────────────

    fun setDetectorMode(mode: DetectorMode, context: android.content.Context? = null) {
        if (_sessionState.value != SessionState.IDLE) return
        when (mode) {
            DetectorMode.FAKE -> {
                tfliteDetector?.close()
                tfliteDetector = null
                detector = FakeDetector()
                _detectorMode.value = DetectorMode.FAKE
                _error.value = null
            }
            DetectorMode.TFLITE -> {
                if (context == null) return
                try {
                    val tfd = TfliteDetector(context.applicationContext)
                    tfliteDetector?.close()
                    tfliteDetector = tfd
                    detector = tfd
                    _detectorMode.value = DetectorMode.TFLITE
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = "TFLite load failed: ${e.message}"
                }
            }
        }
    }

    fun clearError() { _error.value = null }

    // ── Recording control ─────────────────────────────────────────────────────

    fun armRecording() {
        if (!canArm(_sessionState.value, _recordingState.value)) return
        val rec = recorderFactory()
        recorder = rec
        viewModelScope.launch(Dispatchers.IO) {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
            _recordingState.value = RecordingState.ARMED
        }
    }

    fun disarmRecording() {
        if (!canDisarm(_recordingState.value)) return
        val rec = recorder ?: return
        _recordingState.value = RecordingState.FINALIZING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            _lastRecording.value = result
            _recordingState.value = RecordingState.IDLE
            recorder = null
        }
    }

    fun clearLastRecording() { _lastRecording.value = null }

    // ── Session control ───────────────────────────────────────────────────────

    fun start() {
        if (_sessionState.value != SessionState.IDLE) return
        _sessionState.value = SessionState.RUNNING
        lastPreviewFrameMs = 0L
        lastInferenceMs = 0L
        videoSource.start()

        armRecording()

        pipelineJob = viewModelScope.launch {
            // Latest frame shared between the two coroutines below.
            // StateFlow is conflated — if inference is slow, it naturally picks up
            // the most recent frame instead of queuing up every intermediate one.
            val latestFrame = MutableStateFlow<VideoFrame?>(null)

            // Coroutine 1: collect frames at full source speed, track preview FPS.
            launch {
                videoSource.frames.collect { frame ->
                    val now = System.currentTimeMillis()
                    if (lastPreviewFrameMs > 0L) {
                        val dt = now - lastPreviewFrameMs
                        if (dt > 0) _previewFps.value =
                            _previewFps.value * 0.85f + (1000f / dt) * 0.15f
                    }
                    lastPreviewFrameMs = now
                    latestFrame.value = frame
                }
            }

            // Coroutine 2: run inference on latest available frame; skips frames
            // automatically when inference is slower than the source frame rate.
            launch(Dispatchers.Default) {
                latestFrame.filterNotNull().collect { frame ->
                    val results = detector.detect(frame)
                    _detections.value = results

                    if (_recordingState.value == RecordingState.ARMED) {
                        recorder?.onFrame(frame, results)
                    }

                    val now = System.currentTimeMillis()
                    if (lastInferenceMs > 0L) {
                        val dt = now - lastInferenceMs
                        if (dt > 0) _inferenceFps.value =
                            _inferenceFps.value * 0.85f + (1000f / dt) * 0.15f
                    }
                    lastInferenceMs = now
                }
            }
        }
    }

    fun stop() {
        if (_sessionState.value != SessionState.RUNNING) return
        if (canDisarm(_recordingState.value)) disarmRecording()
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
        if (canDisarm(_recordingState.value)) disarmRecording()
        videoSource.stop()
        pipelineJob?.cancel()
        tfliteDetector?.close()
    }
}
