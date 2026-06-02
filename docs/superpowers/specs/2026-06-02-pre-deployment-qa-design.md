# Pre-Deployment QA & Hardening Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Harden DroneEdge for first deployment to a Samsung Galaxy Tab S10+, sideloaded via USB. No new features — fix what can silently fail on real hardware.

**Scope:** 85 unit tests exist and pass. Real DJI stream testing will happen after deployment; this sprint covers what can be verified now. Primary sources in field use: DJI Goggles + FileReplay. Camera and USB/UVC sources must be kept but are secondary.

**Deployment method:** Sideloaded APK transferred via USB drive. No Play Store. Debug signing is acceptable for now.

---

## Area 1: Manifest Hardening

**File:** `app/src/main/AndroidManifest.xml`

Add two attributes to the `<activity>` tag for `MainActivity`:

```xml
android:screenOrientation="sensorLandscape"
android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"
```

- `sensorLandscape` locks the UI to landscape but allows the device to flip between both landscape orientations (good for a tablet picked up from either side). The drone video is always landscape-proportioned.
- `configChanges` prevents Android from restarting `MainActivity` on screen config changes (keyboard attach, screen size renegotiation). Without this, an active live session is destroyed mid-flight.

---

## Area 2: Camera Runtime Permission Gate

**Files:** `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt`

`CAMERA` is declared in the manifest but there is no runtime permission request anywhere. On Android 6+, selecting the Camera source without permission causes `CameraVideoSource` to throw.

**Fix:** In `SourceSheet.kt`, wire up `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` scoped to `Manifest.permission.CAMERA`. When the user taps the Camera option:
1. Check `ContextCompat.checkSelfPermission(context, CAMERA)`
2. If `PERMISSION_GRANTED`, call `onCameraSelected` as normal
3. If not granted, fire the launcher; on grant callback call `onCameraSelected`; on denial, surface an error via the existing `vm.reportError()` path and leave the source unchanged

DJI and USB sources do not need this — they use the `UsbManager` permission system already handled in `handleUsbLaunchIntent`.

---

## Area 3: VideoSessionRecorder Crash Fix

**File:** `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt`

### Bug: `stop()` crashes when no frames were recorded

`MediaMuxer.stop()` throws `IllegalStateException` if the muxer was never started. The muxer is only started inside `drainEncoder()` when `INFO_OUTPUT_FORMAT_CHANGED` is received, which only happens after at least one encoded frame arrives.

If a session is armed and immediately stopped (zero frames — e.g., DJI cable unplugged right away, or user taps stop before any frames arrive), `stop()` calls `muxer?.stop()` on an unstarted muxer and crashes.

**Fix in `stop()`:** Guard the stop call with the existing `muxerStarted` flag:
```kotlin
if (muxerStarted) muxer?.stop()
muxer?.release()
muxer = null
```

### Fix: `start()` error path leaks resources

If encoder setup fails partway through `start()` (unsupported codec on a specific device, MediaStore insertion failure, etc.), partially-initialized resources are never released. Wrap the `start()` body in `try/catch` that calls `releaseResources()` on failure and re-throws, so the ViewModel can surface an error instead of leaving dangling objects.

Extract a private `releaseResources()` helper (already needed by both `stop()` and the new catch block) that nulls out encoder, muxer, videoFd, and jsonWriter safely.

---

## Area 4: Unit Tests

**Files:**
- `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt` (add 3 tests)
- `app/src/test/java/com/yotam/droneedge/video/FakeVideoSourceTest.kt` (new file, 2 tests)

### LiveViewModelTest additions — background auto-stop

```kotlin
@Test
fun `onAppBackgrounded when not armed is a no-op`()

@Test
fun `onAppForegrounded within 10s cancels the auto-stop`()

@Test
fun `onAppBackgrounded while armed stops full session after 10 seconds`()
// Uses advanceTimeBy(10_000L); asserts sessionState == IDLE after timer fires
```

All three use `UnconfinedTestDispatcher` and `FakeSessionRecorder` already set up in the existing `@Before`.

### FakeVideoSourceTest (new)

```kotlin
@Test
fun `frames flow emits after start`()
// start(), collect with take(3), assert 3 frames received with incrementing index

@Test
fun `stop halts the flow`()
// start(), collect in a coroutine, stop(), assert flow completes
```

`FakeVideoSource` is pure Kotlin with no Android dependencies — JVM testable with `runTest`.

### Out of scope for JVM tests

- `RecordingsViewModel` — queries `ContentResolver`/MediaStore; requires Robolectric or a real device
- `VideoSessionRecorder` internals — uses `MediaCodec`/`MediaMuxer`; Android platform only
- All UI screens — Compose UI tests require an instrumented runner; covered by manual testing on device

---

## Area 5: Release Build

**Files:**
- `app/proguard-rules.pro`
- `app/build.gradle.kts`

### ProGuard rules for TFLite/LiteRT

Without keep rules, R8 strips TFLite's native interop classes at build time and the app crashes at model load. Add to `proguard-rules.pro`:

```
-keep class org.tensorflow.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-dontwarn org.tensorflow.**
```

### Release build type

In `build.gradle.kts` `android { buildTypes { release { ... } } }`:

```kotlin
isMinifyEnabled = true
proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
signingConfig = signingConfigs.getByName("debug")
```

Using `signingConfigs.debug` is intentional — it allows sideloading without a dedicated keystore. A production keystore can be added later when targeting the Play Store.

**Verification:** `./gradlew assembleRelease` must build cleanly and the resulting APK must launch and load the TFLite model without crashing.

---

## Success Criteria

- [ ] App launches in landscape and stays in landscape on the Tab S10+
- [ ] Rotating / flipping the tablet does not restart or kill an active session
- [ ] Tapping Camera source without permission shows an error; does not crash
- [ ] Starting and immediately stopping a session does not crash
- [ ] All 85 + 5 new unit tests pass (`./gradlew test`)
- [ ] `./gradlew assembleRelease` builds successfully
- [ ] Release APK installs and runs on the Tab S10+ via sideload
