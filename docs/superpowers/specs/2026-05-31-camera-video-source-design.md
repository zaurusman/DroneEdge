# CameraVideoSource Design

## Goal

Add `CameraVideoSource` — a `VideoSource` implementation that delivers live frames from the device camera (back or front) using CameraX `ImageAnalysis`. Primary use case: test the full detection + recording pipeline on an Android emulator (using the Mac's webcam) without needing a physical USB camera.

---

## Architecture

`CameraVideoSource` sits alongside the existing video sources and implements the same `VideoSource` interface. The rest of the pipeline (detection, recording, ViewModel, LiveScreen) is unchanged.

```
VideoSource (interface)
├── FakeVideoSource
├── FileReplayVideoSource
├── UsbUvcVideoSource
└── CameraVideoSource  ← new
```

---

## New File

### `video/CameraVideoSource.kt`

```kotlin
class CameraVideoSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val facing: Int = CameraSelector.LENS_FACING_BACK,
) : VideoSource
```

**Frame delivery:** Uses `callbackFlow`. On collection:
1. Awaits `ProcessCameraProvider` on the Main dispatcher.
2. Builds an `ImageAnalysis` use case with `STRATEGY_KEEP_ONLY_LATEST` (drops frames the pipeline can't keep up with — no queuing).
3. Sets a single-thread executor analyzer: calls `proxy.toBitmap()`, updates `width`/`height`, sends a `VideoFrame` via `trySend`, closes the proxy.
4. Binds the use case + `CameraSelector` (back or front) to the `lifecycleOwner` on the Main dispatcher.
5. `awaitClose` unbinds the analysis use case and shuts down the executor — runs automatically when the collecting coroutine is cancelled (i.e. when `pipelineJob?.cancel()` fires during `stop()`).

**`start()` / `stop()`:** `start()` resets the frame index. `stop()` is a no-op — cleanup is owned by the flow's `awaitClose`, triggered by coroutine cancellation. This matches how the existing source-selection contract already works.

**`width` / `height`:** Initialised to 1280×720, updated from the first decoded frame's dimensions.

---

## Modified Files

### `AndroidManifest.xml`

Add:
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

### `app/build.gradle.kts`

Add two CameraX dependencies (via version catalog):
- `androidx.camera:camera-camera2`
- `androidx.camera:camera-lifecycle`

(`camera-core` is a transitive dep of both and does not need a separate entry.)

### `libs.versions.toml`

Add CameraX version entry and two library aliases.

### `LiveViewModel.kt`

Add state and methods:

```kotlin
private val _cameraFacing = MutableStateFlow<Int?>(null)
val cameraFacing: StateFlow<Int?> = _cameraFacing.asStateFlow()

fun useCameraSource(facing: Int, context: Context, lifecycleOwner: LifecycleOwner)
fun clearCameraSource()
```

`useCameraSource` creates a `CameraVideoSource`, sets `_cameraFacing`, clears `_videoUri` and `_usbDevice`.

`clearCameraSource` clears `_cameraFacing`, swaps back to `FakeVideoSource` (IDLE only — same pattern as `clearUsbSource`).

All four existing source-selection methods (`useFakeSource`, `useFileSource`, `useUsbSource`, `useCameraSource`) each clear the other three state fields, keeping source selection coherent.

### `LiveScreen.kt`

**Permission:** `rememberLauncherForActivityResult(RequestPermission())` for `Manifest.permission.CAMERA`. On grant, calls `vm.useCameraSource(LENS_FACING_BACK, context, lifecycleOwner)`.

**Button:** When IDLE and `cameraFacing == null` — "Camera" button. Checks `ContextCompat.checkSelfPermission`; if granted connects directly, otherwise launches the permission request. When `cameraFacing != null` — "Clear Cam" button (red, calls `vm.clearCameraSource()`).

**Badge:** Extends the existing `when` expression:
```
usbDevice != null  → "USB: <name>"
cameraFacing != null → "CAM: back" | "CAM: front"
videoUri != null   → "FILE: <name>"
else               → "FAKE SOURCE"
```

---

## Data Flow

```
Device camera
    │  (CameraX ImageAnalysis executor thread)
    ▼
CameraVideoSource.frames  (callbackFlow)
    │  proxy.toBitmap() → VideoFrame
    ▼
LiveViewModel coroutine 1  (preview FPS tracking)
    │
    ├──▶ latestFrame StateFlow
    │         │
    │         ▼
    │    LiveViewModel coroutine 2  (TFLite detection + recording)
    │
    └──▶ LiveScreen  (bitmap rendered via DetectionOverlay)
```

---

## Permissions

`CAMERA` is a dangerous runtime permission (granted by the user at first use). The request is initiated from `LiveScreen` when the user taps "Camera". If denied, no error is surfaced beyond the system dialog — the button simply does nothing. If permanently denied, the system dialog does not appear; the app relies on the standard Android system behaviour (user can re-enable in Settings). No custom rationale dialog is added for this phase.

---

## Dependencies

| Library | Why |
|---------|-----|
| `androidx.camera:camera-camera2` | CameraX backend — binds CameraX to the Camera2 API |
| `androidx.camera:camera-lifecycle` | `ProcessCameraProvider` + lifecycle binding |

Version: use the latest stable CameraX release (1.4.x as of writing). Both libraries are official Jetpack libraries with the same versioning cadence as the Lifecycle and Compose libraries already in the project.

---

## Testing

**Unit tests:** None for `CameraVideoSource` itself — hardware-dependent. The `VideoSource` interface contract is verified indirectly via `FakeVideoSource` (already tested) and the shared pipeline in `LiveViewModel`.

**Manual verification (emulator):**
1. Install on emulator (`./gradlew installDebug`)
2. Tap "Camera" → permission dialog appears → grant
3. Badge changes to "CAM: back"
4. Tap Start → preview FPS counter rises; Mac webcam feed visible with bounding boxes
5. Tap Stop → session ends cleanly
6. Tap "Clear Cam" → reverts to "FAKE SOURCE"

**Manual verification (physical device — later):**
- Back camera streams live drone-video frames through detection
- Front/back toggle (if added in a future iteration)

---

## Out of Scope

- Front/back toggle button in the UI (can be added trivially later by passing `LENS_FACING_FRONT`)
- Zoom, flash, or other camera controls
- Preview surface rendering via CameraX `Preview` use case (we use `ImageAnalysis` only; the existing `DetectionOverlay` + Bitmap rendering in `LiveScreen` handles display)
- Camera2 fallback for devices without CameraX support (all API 21+ devices are supported)
