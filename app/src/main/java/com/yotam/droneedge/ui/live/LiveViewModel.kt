package com.yotam.droneedge.ui.live

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.detection.Detector
import com.yotam.droneedge.detection.FakeDetector
import com.yotam.droneedge.detection.TfliteDetector
import com.yotam.droneedge.recording.RecordingResult
import com.yotam.droneedge.recording.SessionRecorder
import com.yotam.droneedge.recording.VideoSessionRecorder
import com.yotam.droneedge.recording.renameSession
import com.yotam.droneedge.recording.sanitizeSessionName
import com.yotam.droneedge.video.CameraVideoSource
import com.yotam.droneedge.video.FakeVideoSource
import com.yotam.droneedge.video.FileReplayVideoSource
import com.yotam.droneedge.video.DjiGogglesVideoSource
import com.yotam.droneedge.video.UsbUvcVideoSource
import com.yotam.droneedge.video.VideoFrame
import com.yotam.droneedge.video.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // ── USB device (null = no USB source) ────────────────────────────────────
    private val _usbDevice = MutableStateFlow<UsbDevice?>(null)
    val usbDevice: StateFlow<UsbDevice?> = _usbDevice.asStateFlow()

    // ── DJI Goggles device (null = no DJI source) ────────────────────────────
    private val _djiDevice = MutableStateFlow<UsbDevice?>(null)
    val djiDevice: StateFlow<UsbDevice?> = _djiDevice.asStateFlow()

    // ── Camera facing (null = no camera source) ───────────────────────────────
    private val _cameraFacing = MutableStateFlow<Int?>(null)
    val cameraFacing: StateFlow<Int?> = _cameraFacing.asStateFlow()

    // ── Detector mode ─────────────────────────────────────────────────────────
    private val _detectorMode = MutableStateFlow(DetectorMode.FAKE)
    val detectorMode: StateFlow<DetectorMode> = _detectorMode.asStateFlow()

    // ── Active external model file (null = bundled asset) ─────────────────────
    private val _activeModelFile = MutableStateFlow<File?>(null)
    val activeModelFile: StateFlow<File?> = _activeModelFile.asStateFlow()

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

    // ── Pending rename (awaiting user input before showing snackbar) ──────────
    private val _pendingRename = MutableStateFlow<RecordingResult?>(null)
    val pendingRename: StateFlow<RecordingResult?> = _pendingRename.asStateFlow()

    // ── Detection results ─────────────────────────────────────────────────────
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    // ── FPS ───────────────────────────────────────────────────────────────────
    private val _previewFps = MutableStateFlow(0f)
    val previewFps: StateFlow<Float> = _previewFps.asStateFlow()

    private val _inferenceFps = MutableStateFlow(0f)
    val inferenceFps: StateFlow<Float> = _inferenceFps.asStateFlow()

    // ── Latest frame (exposed for camera preview rendering) ──────────────────
    private val _latestFrame = MutableStateFlow<VideoFrame?>(null)
    val latestFrame: StateFlow<VideoFrame?> = _latestFrame.asStateFlow()

    private val previewFrameTimes = ArrayDeque<Long>()
    private val inferenceFrameTimes = ArrayDeque<Long>()
    private var pipelineJob: Job? = null

    // ── Recording elapsed timer ───────────────────────────────────────────────
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()
    private var timerJob: Job? = null

    // ── Source selection (only while IDLE) ────────────────────────────────────

    fun useFileSource(uri: Uri, context: android.content.Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource      = FileReplayVideoSource(uri, context.applicationContext)
        _videoUri.value  = uri
        _usbDevice.value = null
        _cameraFacing.value = null
        _djiDevice.value    = null
    }

    fun useFakeSource() {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource      = FakeVideoSource()
        _videoUri.value  = null
        _usbDevice.value = null
        _cameraFacing.value = null
        _djiDevice.value    = null
    }

    fun useUsbSource(device: UsbDevice, context: android.content.Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource      = UsbUvcVideoSource(context.applicationContext, device)
        _usbDevice.value = device
        _videoUri.value  = null
        _cameraFacing.value = null
        _djiDevice.value    = null
    }

    fun clearUsbSource() {
        if (_usbDevice.value == null) return
        _usbDevice.value = null
        if (_sessionState.value == SessionState.IDLE) {
            videoSource = FakeVideoSource()
        }
        // If RUNNING, the USB source will exhaust its error counter and stop naturally.
        // Fix 1 (flow-completion auto-stop) will handle session cleanup.
    }

    fun useDjiSource(device: UsbDevice, context: android.content.Context) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource         = DjiGogglesVideoSource(context.applicationContext, device)
        _djiDevice.value    = device
        _videoUri.value     = null
        _usbDevice.value    = null
        _cameraFacing.value = null
    }

    fun clearDjiSource() {
        if (_djiDevice.value == null) return
        _djiDevice.value = null
        if (_sessionState.value == SessionState.IDLE) videoSource = FakeVideoSource()
    }

    fun useCameraSource(facing: Int, context: Context, lifecycleOwner: LifecycleOwner) {
        if (_sessionState.value != SessionState.IDLE) return
        videoSource         = CameraVideoSource(context.applicationContext, lifecycleOwner, facing)
        _cameraFacing.value = facing
        _videoUri.value     = null
        _usbDevice.value    = null
        _djiDevice.value    = null
    }

    fun clearCameraSource() {
        if (_cameraFacing.value == null) return
        _cameraFacing.value = null
        if (_sessionState.value == SessionState.IDLE) {
            videoSource = FakeVideoSource()
        }
        // If RUNNING, the camera flow completes when pipelineJob is cancelled (via stop()).
        // CameraVideoSource has no self-exit mechanism; the session must be stopped manually.
    }

    /** Called from MainActivity when the app is launched by a USB_DEVICE_ATTACHED intent. */
    fun handleUsbLaunchIntent(intent: android.content.Intent, context: android.content.Context) {
        val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        } ?: return
        val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        if (usbManager.hasPermission(device)) {
            if (device.vendorId == DjiGogglesVideoSource.VENDOR_ID) useDjiSource(device, context)
            else useUsbSource(device, context)
        }
    }

    fun reportError(message: String) {
        _error.value = message
    }

    // ── Detector selection (only while IDLE) ──────────────────────────────────

    fun setDetectorMode(mode: DetectorMode, context: android.content.Context? = null, modelFile: File? = null) {
        if (_sessionState.value != SessionState.IDLE) return
        when (mode) {
            DetectorMode.NO_MODEL -> {
                tfliteDetector?.close()
                tfliteDetector = null
                detector = object : Detector { override suspend fun detect(frame: VideoFrame) = emptyList<Detection>() }
                _detectorMode.value = DetectorMode.NO_MODEL
                _activeModelFile.value = null
                _error.value = null
            }
            DetectorMode.FAKE -> {
                tfliteDetector?.close()
                tfliteDetector = null
                detector = FakeDetector()
                _detectorMode.value = DetectorMode.FAKE
                _activeModelFile.value = null
                _error.value = null
            }
            DetectorMode.TFLITE -> {
                if (context == null) return
                try {
                    val tfd = TfliteDetector(context.applicationContext, modelFile = modelFile)
                    tfliteDetector?.close()
                    tfliteDetector = tfd
                    detector = tfd
                    _detectorMode.value = DetectorMode.TFLITE
                    _activeModelFile.value = modelFile
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = "TFLite load failed: ${e.message}"
                }
            }
        }
    }

    fun clearError() { _error.value = null }

    // ── Background auto-stop ──────────────────────────────────────────────────

    private var backgroundStopJob: Job? = null

    fun onAppBackgrounded() {
        if (_recordingState.value != RecordingState.ARMED) return
        backgroundStopJob = viewModelScope.launch {
            delay(10_000L)
            disarmRecording()
        }
    }

    fun onAppForegrounded() {
        backgroundStopJob?.cancel()
        backgroundStopJob = null
    }

    // ── Recording control ─────────────────────────────────────────────────────

    fun armRecording() {
        if (!canArm(_sessionState.value, _recordingState.value)) return
        val rec = recorderFactory()
        recorder = rec
        _recordingElapsedMs.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _recordingElapsedMs.value += 1000L
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
            _recordingState.value = RecordingState.ARMED
        }
    }

    fun disarmRecording() {
        timerJob?.cancel()
        timerJob = null
        if (!canDisarm(_recordingState.value)) return
        val rec = recorder ?: return
        _recordingState.value = RecordingState.FINALIZING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            _pendingRename.value = result
            _recordingState.value = RecordingState.IDLE
            recorder = null
        }
    }

    fun clearLastRecording() { _lastRecording.value = null }

    fun skipNaming(result: RecordingResult) {
        _pendingRename.value = null
        _lastRecording.value = result
    }

    fun finalizeSessionName(result: RecordingResult, name: String) {
        val sanitized = sanitizeSessionName(name)
        if (sanitized != null && sanitized != result.sessionId && result.videoUri != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    renameSession(getApplication(), result.videoUri, result.sessionId, sanitized)
                }
                _pendingRename.value = null
                _lastRecording.value = result.copy(sessionId = sanitized)
            }
        } else {
            _pendingRename.value = null
            _lastRecording.value = result
        }
    }

    // ── Session control ───────────────────────────────────────────────────────

    fun start() {
        if (_sessionState.value != SessionState.IDLE) return
        _sessionState.value = SessionState.RUNNING
        previewFrameTimes.clear()
        inferenceFrameTimes.clear()
        videoSource.start()

        armRecording()

        pipelineJob = viewModelScope.launch {
            // Coroutine 1: collect frames at full source speed, track preview FPS, record.
            launch {
                videoSource.frames.collect { frame ->
                    val now = System.currentTimeMillis()
                    previewFrameTimes.addLast(now)
                    val cutoff = now - 1000L
                    while (previewFrameTimes.isNotEmpty() && previewFrameTimes.first() < cutoff)
                        previewFrameTimes.removeFirst()
                    _previewFps.value = previewFrameTimes.size.toFloat()
                    _latestFrame.value = frame

                    if (_recordingState.value == RecordingState.ARMED) {
                        recorder?.onFrame(frame, _detections.value)
                    }
                }
                // Flow ended without an explicit stop() — source disconnected or failed.
                if (_sessionState.value == SessionState.RUNNING) {
                    _error.value = "Video source disconnected"
                    stop()
                }
            }

            // Coroutine 2: run inference on latest available frame; skips frames
            // automatically when inference is slower than the source frame rate.
            launch(Dispatchers.Default) {
                _latestFrame.filterNotNull().collect { frame ->
                    val results = detector.detect(frame)
                    _detections.value = results

                    val now = System.currentTimeMillis()
                    inferenceFrameTimes.addLast(now)
                    val cutoff = now - 1000L
                    while (inferenceFrameTimes.isNotEmpty() && inferenceFrameTimes.first() < cutoff)
                        inferenceFrameTimes.removeFirst()
                    _inferenceFps.value = inferenceFrameTimes.size.toFloat()
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
        timerJob?.cancel()
        timerJob = null
        _recordingElapsedMs.value = 0L
        _detections.value = emptyList()
        _previewFps.value = 0f
        _inferenceFps.value = 0f
        _latestFrame.value = null
        _sessionState.value = SessionState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        if (canDisarm(_recordingState.value)) disarmRecording()
        videoSource.stop()
        pipelineJob?.cancel()
        tfliteDetector?.close()
    }
}
