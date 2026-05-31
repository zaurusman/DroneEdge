# CameraVideoSource Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `CameraVideoSource` — a `VideoSource` implementation that streams live frames from the device camera (back or front) via CameraX `ImageAnalysis`, enabling end-to-end pipeline testing on an Android emulator using the Mac's webcam.

**Architecture:** `CameraVideoSource` implements the `VideoSource` interface using a `callbackFlow` that binds a CameraX `ImageAnalysis` use case to a `LifecycleOwner`. Frame delivery is handled on a single-thread executor; the flow's `awaitClose` tears down the camera. `LiveViewModel` gains a `_cameraFacing` state flow and two new methods (`useCameraSource`, `clearCameraSource`). `LiveScreen` gains a runtime CAMERA permission launcher and "Camera" / "Clear Cam" buttons.

**Tech Stack:** Kotlin, CameraX (`camera-camera2` + `camera-lifecycle` 1.4.x), `callbackFlow`, `suspendCancellableCoroutine` (no guava dependency), Jetpack Compose `rememberLauncherForActivityResult`

---

## File Map

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Add `camerax = "1.4.2"` version + 2 library aliases |
| `app/build.gradle.kts` | Add 2 CameraX `implementation` deps |
| `app/src/main/AndroidManifest.xml` | Add `CAMERA` permission |
| `app/src/main/java/com/yotam/droneedge/video/CameraVideoSource.kt` | **Create** |
| `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` | Add `_cameraFacing`, `useCameraSource`, `clearCameraSource`; update 3 existing methods |
| `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` | Add permission launcher, Camera/Clear Cam buttons, badge case |

---

## Task 1: Branch + Dependencies + Manifest

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create feature branch**

```bash
git checkout main && git pull
git checkout -b feature/camera-video-source
```

- [ ] **Step 2: Add CameraX version and library aliases to `gradle/libs.versions.toml`**

In the `[versions]` block, add after the `tfliteSupport` line:
```toml
# CameraX — device camera access via ImageAnalysis
camerax = "1.4.2"
```

In the `[libraries]` block, add after the `tensorflow-lite-support` line:
```toml
# CameraX — Camera2 backend + lifecycle binding
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
```

- [ ] **Step 3: Add CameraX deps to `app/build.gradle.kts`**

In the `dependencies { }` block, add after the `tensorflow-lite-support` line:
```kotlin
implementation(libs.androidx.camera.camera2)
implementation(libs.androidx.camera.lifecycle)
```

- [ ] **Step 4: Add CAMERA permission to `app/src/main/AndroidManifest.xml`**

Add inside `<manifest>` before `<uses-feature>`:
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

- [ ] **Step 5: Verify Gradle sync compiles**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` — no unresolved reference errors.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "build: add CameraX deps and CAMERA permission"
```

---

## Task 2: `CameraVideoSource` Implementation

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/CameraVideoSource.kt`

There are no unit tests for `CameraVideoSource` itself — the class is hardware-bound and requires a real `ProcessCameraProvider`. Interface compliance is verified via the build + manual testing in Task 5.

- [ ] **Step 1: Create `CameraVideoSource.kt`**

Create `app/src/main/java/com/yotam/droneedge/video/CameraVideoSource.kt` with the following content:

```kotlin
package com.yotam.droneedge.video

import android.content.Context
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraVideoSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val facing: Int = CameraSelector.LENS_FACING_BACK,
) : VideoSource {

    override var width: Int = 1280
        private set
    override var height: Int = 720
        private set

    private var frameIndex = 0L

    override fun start() {
        frameIndex = 0L
    }

    // stop() is a no-op — cleanup is owned by the flow's awaitClose,
    // which fires automatically when pipelineJob?.cancel() is called in LiveViewModel.stop().
    override fun stop() = Unit

    override val frames: Flow<VideoFrame> = callbackFlow {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try { cont.resume(future.get()) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                },
                context.mainExecutor,
            )
        }

        val executor = Executors.newSingleThreadExecutor()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { ia ->
                ia.setAnalyzer(executor) { proxy ->
                    val bmp = proxy.toBitmap()
                    width  = bmp.width
                    height = bmp.height
                    trySend(
                        VideoFrame(
                            index       = frameIndex++,
                            timestampMs = System.currentTimeMillis(),
                            width       = bmp.width,
                            height      = bmp.height,
                            bitmap      = bmp,
                        )
                    )
                    proxy.close()
                }
            }

        val selector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        withContext(Dispatchers.Main) {
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }

        awaitClose {
            provider.unbind(analysis)
            executor.shutdown()
        }
    }
}
```

- [ ] **Step 2: Build to verify no compile errors**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/CameraVideoSource.kt
git commit -m "feat: add CameraVideoSource using CameraX ImageAnalysis"
```

---

## Task 3: `LiveViewModel` Camera State

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add imports to `LiveViewModel.kt`**

Add these imports after the existing import block (after `import kotlinx.coroutines.withContext`):
```kotlin
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner
import com.yotam.droneedge.video.CameraVideoSource
```

Note: `android.content.Context` is already imported via the `android.content.Intent` parameter in `handleUsbLaunchIntent`. Check whether a bare `Context` import exists; add it only if missing.

- [ ] **Step 2: Add `_cameraFacing` state flow**

After the `_usbDevice` / `usbDevice` pair (lines 51–52 in the current file), add:

```kotlin
// ── Camera facing (null = no camera source) ───────────────────────────────
private val _cameraFacing = MutableStateFlow<Int?>(null)
val cameraFacing: StateFlow<Int?> = _cameraFacing.asStateFlow()
```

- [ ] **Step 3: Update `useFileSource` to clear `_cameraFacing`**

The current `useFileSource` body is:
```kotlin
fun useFileSource(uri: Uri, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource      = FileReplayVideoSource(uri, context.applicationContext)
    _videoUri.value  = uri
    _usbDevice.value = null
}
```

Replace it with:
```kotlin
fun useFileSource(uri: Uri, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource        = FileReplayVideoSource(uri, context.applicationContext)
    _videoUri.value    = uri
    _usbDevice.value   = null
    _cameraFacing.value = null
}
```

- [ ] **Step 4: Update `useFakeSource` to clear `_cameraFacing`**

The current `useFakeSource` body is:
```kotlin
fun useFakeSource() {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource      = FakeVideoSource()
    _videoUri.value  = null
    _usbDevice.value = null
}
```

Replace it with:
```kotlin
fun useFakeSource() {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = FakeVideoSource()
    _videoUri.value     = null
    _usbDevice.value    = null
    _cameraFacing.value = null
}
```

- [ ] **Step 5: Update `useUsbSource` to clear `_cameraFacing`**

The current `useUsbSource` body is:
```kotlin
fun useUsbSource(device: UsbDevice, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource      = UsbUvcVideoSource(context.applicationContext, device)
    _usbDevice.value = device
    _videoUri.value  = null
}
```

Replace it with:
```kotlin
fun useUsbSource(device: UsbDevice, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = UsbUvcVideoSource(context.applicationContext, device)
    _usbDevice.value    = device
    _videoUri.value     = null
    _cameraFacing.value = null
}
```

- [ ] **Step 6: Add `useCameraSource` and `clearCameraSource`**

After `clearUsbSource()` (before `handleUsbLaunchIntent`), add:

```kotlin
fun useCameraSource(facing: Int, context: Context, lifecycleOwner: LifecycleOwner) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = CameraVideoSource(context.applicationContext, lifecycleOwner, facing)
    _cameraFacing.value = facing
    _videoUri.value     = null
    _usbDevice.value    = null
}

fun clearCameraSource() {
    if (_cameraFacing.value == null) return
    _cameraFacing.value = null
    if (_sessionState.value == SessionState.IDLE) {
        videoSource = FakeVideoSource()
    }
}
```

- [ ] **Step 7: Build to verify no compile errors**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "feat: add cameraFacing state and useCameraSource/clearCameraSource to LiveViewModel"
```

---

## Task 4: `LiveScreen` Camera UI

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Add imports**

Add these imports to `LiveScreen.kt` after the existing import block:
```kotlin
import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.LocalLifecycleOwner
```

- [ ] **Step 2: Collect `cameraFacing` from the ViewModel**

After the `val usbDevice by vm.usbDevice.collectAsStateWithLifecycle()` line, add:
```kotlin
val cameraFacing    by vm.cameraFacing.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Add `lifecycleOwner` and CAMERA permission launcher**

After `val context = LocalContext.current`, add:
```kotlin
val lifecycleOwner = LocalLifecycleOwner.current

val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
}
```

- [ ] **Step 4: Update the source badge `when` block**

The current badge block is:
```kotlin
when {
    usbDevice != null -> Text(
        text     = "USB: ${usbDevice!!.productName ?: usbDevice!!.deviceName}",
        ...
    )
    videoUri != null -> Text(
        text     = "FILE: ${videoUri!!.lastPathSegment ?: "video"}",
        ...
    )
    else -> HudText("FAKE SOURCE")
}
```

Replace it with:
```kotlin
when {
    usbDevice != null -> Text(
        text     = "USB: ${usbDevice!!.productName ?: usbDevice!!.deviceName}",
        color    = Color(0xFFB0BEC5),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
    cameraFacing != null -> HudText(
        if (cameraFacing == CameraSelector.LENS_FACING_BACK) "CAM: back" else "CAM: front"
    )
    videoUri != null -> Text(
        text     = "FILE: ${videoUri!!.lastPathSegment ?: "video"}",
        color    = Color(0xFFB0BEC5),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
    else -> HudText("FAKE SOURCE")
}
```

- [ ] **Step 5: Add Camera / Clear Cam button**

After the USB Cam / Clear USB block (the `if (sessionState == SessionState.IDLE) { if (usbDevice == null) ... }` block), add a new block for camera:

```kotlin
// Camera connect / clear button (only while idle)
if (sessionState == SessionState.IDLE) {
    if (cameraFacing == null) {
        OutlinedButton(onClick = {
            val permission = Manifest.permission.CAMERA
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
            } else {
                cameraPermissionLauncher.launch(permission)
            }
        }) {
            Text("Camera")
        }
    } else {
        OutlinedButton(
            onClick = { vm.clearCameraSource() },
            colors  = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
        ) {
            Text("Clear Cam")
        }
    }
}
```

- [ ] **Step 6: Build to verify no compile errors**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Run unit tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` — all existing tests pass (no new unit tests for hardware-bound source).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: add Camera/Clear Cam button and CAMERA permission flow to LiveScreen"
```

---

## Task 5: Build Gate + PR

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 3: Push branch and create PR**

```bash
git push -u origin feature/camera-video-source
gh pr create \
  --title "feat: add CameraVideoSource (CameraX ImageAnalysis)" \
  --body "$(cat <<'EOF'
## Summary
- Adds `CameraVideoSource` — streams live frames from device camera (back/front) via CameraX `ImageAnalysis` + `callbackFlow`
- LiveViewModel gains `cameraFacing` state, `useCameraSource()`, and `clearCameraSource()`; all four source-selection methods now clear every other source's state field
- LiveScreen gains a runtime CAMERA permission launcher, "Camera"/"Clear Cam" buttons, and a "CAM: back/front" badge

## Test Plan
- [ ] `./gradlew test` — all unit tests pass
- [ ] `./gradlew installDebug` on emulator
- [ ] Tap **Camera** → permission dialog appears → grant
- [ ] Badge changes to "CAM: back"
- [ ] Tap **Start** → preview FPS counter rises; Mac webcam feed visible with bounding boxes
- [ ] Tap **Stop** → session ends cleanly
- [ ] Tap **Clear Cam** → reverts to "FAKE SOURCE"
- [ ] Pick Video / USB Cam each clear the camera badge when selected

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: After PR merged — update memory**

After the PR is merged to `main`, note Phase 6 (CameraVideoSource) is complete in project memory.
