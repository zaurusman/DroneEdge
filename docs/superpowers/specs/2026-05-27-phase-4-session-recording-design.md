# Phase 4: Session Recording and Metadata

**Date:** 2026-05-27
**Status:** Approved

---

## Summary

Phase 4 adds user-triggered session recording to DroneEdge. While a session is running, the user can arm a REC toggle to begin saving an annotated MP4 (bounding boxes burned in) and a newline-delimited JSON detection log to `Movies/DroneEdge/<session>/` on public external storage. Recording is independent of the session start/stop — you can watch without recording, or arm/disarm recording mid-session.

---

## Architecture

A new `SessionRecorder` layer sits alongside the existing `VideoSource` and `Detector` layers inside `LiveViewModel`. When REC is armed, each inference result is forwarded to the recorder. When REC is disarmed (or Stop is pressed), the recorder finalizes the MP4 and JSON files.

```
VideoSource ──frames──► [preview FPS counter]
                │
                ▼
         latestFrame (StateFlow, conflated)
                │
                ▼
           Detector ──────────────────────────────► _detections (UI overlay)
                │
                ▼
        if ARMED: SessionRecorder.onFrame(frame, detections)
                │
          ┌─────┴────────────────────┐
          │  VideoSessionRecorder    │
          │  ┌─────────────────────┐ │
          │  │ annotated bitmap    │ │
          │  │ → MediaCodec/H.264  │ │
          │  │ → MediaMuxer/MP4    │ │
          │  └─────────────────────┘ │
          │  ┌─────────────────────┐ │
          │  │ detections.json     │ │
          │  └─────────────────────┘ │
          └──────────────────────────┘
```

### New files

| File | Purpose |
|------|---------|
| `recording/SessionRecorder.kt` | Interface |
| `recording/VideoSessionRecorder.kt` | MediaCodec + MediaMuxer implementation |
| `recording/RecordingResult.kt` | Data class returned after finalization |
| `ui/live/RecordingState.kt` | IDLE / ARMED / FINALIZING enum |

### Changed files

| File | Change |
|------|--------|
| `LiveViewModel.kt` | Owns `SessionRecorder`, exposes `recordingState`, adds `armRecording()` / `disarmRecording()` |
| `LiveScreen.kt` | REC toggle button, "Saving…" indicator, completion snackbar |
| `AndroidManifest.xml` | `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` |
| `VideoSource.kt` | Add `val width: Int` and `val height: Int` properties to the interface |

---

## Data Model

### `RecordingState`

```kotlin
enum class RecordingState {
    IDLE,        // no recorder active
    ARMED,       // actively encoding frames and writing JSON
    FINALIZING,  // stop() called, MediaMuxer draining
}
```

`SessionState` (IDLE / RUNNING / STOPPING) is unchanged. `RecordingState` is orthogonal — a session can be RUNNING with recording IDLE (watching without recording) or RUNNING with recording ARMED.

### `RecordingResult`

```kotlin
data class RecordingResult(
    val videoUri: Uri,
    val jsonUri: Uri,
    val frameCount: Int,
    val durationMs: Long,
)
```

### `DetectionEvent` (JSON schema)

One JSON object per line in `detections.json`. Frames with zero detections are omitted.

```json
{"frameIndex":142,"timestampMs":4733,"detections":[{"label":"person","confidence":0.87,"left":0.12,"top":0.34,"right":0.45,"bottom":0.78}]}
```

### `SessionRecorder` interface

```kotlin
interface SessionRecorder {
    suspend fun start(width: Int, height: Int, fps: Int, context: Context)
    suspend fun onFrame(frame: VideoFrame, detections: List<Detection>)
    suspend fun stop(): RecordingResult
}
```

---

## `VideoSessionRecorder` Implementation

### Bitmap annotation

Before encoding, each frame's bitmap is copied and bounding boxes are drawn onto the copy using `android.graphics.Canvas` + `Paint`. The original bitmap is untouched. If `VideoFrame.bitmap` is null (e.g. `FakeVideoSource`), `onFrame()` is a no-op — the frame is skipped silently and not counted toward `frameCount`.

### MediaCodec encoding

- Input mode: ByteBuffer with `COLOR_FormatYUV420Flexible`
- Each annotated ARGB bitmap is converted to NV12 (Y plane, then interleaved UV plane) and written into a dequeued input buffer
- Presentation timestamp: `VideoFrame.timestampMs` converted to microseconds
- Avoids EGL/OpenGL complexity; works reliably on all API 28+ devices

### MediaMuxer

- Started after MediaCodec emits its first output buffer with `BUFFER_FLAG_CODEC_CONFIG`
- Output format: `MUXER_OUTPUT_MPEG_4`
- Released in `stop()` after all pending output buffers are drained

### JSON writing

- `BufferedWriter` opened at `start()`, flushed on every frame that has detections, closed in `stop()`

### Storage

Session folder name: `session_yyyyMMdd_HHmmss`

| API level | MP4 | JSON |
|-----------|-----|------|
| 29+ | `MediaStore.Video.Media` with `RELATIVE_PATH = "Movies/DroneEdge/<session>/"`, `IS_PENDING = 1` until `stop()` completes | `MediaStore.Files` in the same folder |
| 28 | Direct `File` under `Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES)/DroneEdge/<session>/` | Same folder, direct `File` I/O |

`WRITE_EXTERNAL_STORAGE` is declared in the manifest with `android:maxSdkVersion="28"` (not needed on API 29+).

### Threading

`onFrame()` is a suspending function that runs its encoding work on `Dispatchers.IO`. It does not block the inference coroutine on `Dispatchers.Default`. If encoding falls behind, the existing `latestFrame` StateFlow conflation drops intermediate frames at the inference level, so the recorder receives fewer frames rather than queuing up.

---

## ViewModel Changes

### New state

```kotlin
private val _recordingState = MutableStateFlow(RecordingState.IDLE)
val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

private val _lastRecording = MutableStateFlow<RecordingResult?>(null)
val lastRecording: StateFlow<RecordingResult?> = _lastRecording.asStateFlow()

private var recorder: SessionRecorder? = null
```

### `armRecording(context)`

- Guard: only when `SessionState.RUNNING` and `RecordingState.IDLE`
- Creates `VideoSessionRecorder`, calls `start()` with dimensions from `videoSource.width` / `videoSource.height` (new `VideoSource` interface properties) and `targetFps = 30`
- Sets `_recordingState = ARMED`

### `disarmRecording()`

- Guard: only when `RecordingState.ARMED`
- Sets `_recordingState = FINALIZING`
- Launches coroutine on `Dispatchers.IO` to call `recorder.stop()`
- Stores result in `_lastRecording`, resets `_recordingState = IDLE`

### Inference coroutine change

One additional line after `_detections.value = results`:

```kotlin
if (_recordingState.value == RecordingState.ARMED) {
    recorder?.onFrame(frame, results)
}
```

### `stop()` change

If `RecordingState.ARMED` when session stops, `disarmRecording()` is called first to finalize the file cleanly.

### `onCleared()`

Calls `disarmRecording()` if `RecordingState.ARMED` (app backgrounded mid-recording).

### `clearLastRecording()`

Resets `_lastRecording` to `null` after the completion snackbar is dismissed.

---

## UI Changes

The REC button appears in the bottom controls row **only while `SessionState.RUNNING`**, to the left of Stop.

| `RecordingState` | Button appearance | Tap action |
|-----------------|-------------------|------------|
| `IDLE` | Outlined, label "REC", neutral color | `vm.armRecording(context)` |
| `ARMED` | Filled red, label "● REC" | `vm.disarmRecording()` |
| `FINALIZING` | Disabled, label "Saving…" | — |

**Completion snackbar:** when `lastRecording` transitions from `null` to a value, a `Snackbar` shows `"Saved to Movies/DroneEdge/<session>/"` with a "Dismiss" action that calls `vm.clearLastRecording()`.

No other UI changes — FPS HUD, source badges, detector toggle, and file picker are unchanged.

---

## Testing

### `YuvConversionTest` (JVM unit test)

Verifies the ARGB→NV12 conversion produces the correct byte layout for a known pixel pattern. Regression guard for the most error-prone part of the encoding path.

### `DetectionEventSerializationTest` (JVM unit test)

Verifies `DetectionEvent` serializes to the expected JSON format and produces valid newline-delimited JSON. Uses `org.json` (bundled with Android SDK).

### `RecordingStateTransitionTest` (JVM unit test, `kotlinx-coroutines-test`)

Uses a `FakeSessionRecorder` (test double that records calls without doing I/O) to drive the ViewModel state machine:
- `armRecording()` only works when `SessionState.RUNNING` + `RecordingState.IDLE`
- `disarmRecording()` only works when `RecordingState.ARMED`
- `stop()` while ARMED triggers finalization
- `onCleared()` cleans up an active recording

No instrumented tests for this phase — MediaCodec and MediaMuxer require a device or emulator; the encoding path is validated by manual integration testing (record a clip, verify it appears in Movies/DroneEdge and plays back with visible bounding boxes).

---

## Out of Scope (Phase 5+)

- MediaCodec-based video decoding in `FileReplayVideoSource` (currently uses `MediaMetadataRetriever`)
- USB/UVC source
- DJI goggles source
- Optional per-detection screenshot exports
