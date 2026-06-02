# Pre-Deployment QA & Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden DroneEdge for first deployment on a Samsung Galaxy Tab S10+ — lock orientation, fix a real recorder crash, strengthen error handling, add missing unit tests, and produce a working release APK.

**Architecture:** Five independent areas tackled in order: manifest config, ViewModel ARMED-state fix (enables testability + start-failure handling), VideoSessionRecorder crash fix, new unit tests, release build. Each task is independently committable.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidViewModel, Coroutines/Flow, MediaCodec/MediaMuxer, TFLite, JUnit4, kotlinx-coroutines-test

---

> **Note — Area 2 (Camera Runtime Permission) is already implemented.**
> `LiveScreen.kt` lines 120–124 and 361–366 already contain `rememberLauncherForActivityResult(RequestPermission())`, the `checkSelfPermission` gate, and the `vm.reportError()` denial path. No task needed.

---

## File Map

| File | Change |
|------|--------|
| `app/src/main/AndroidManifest.xml` | Add `screenOrientation` + `configChanges` to `<activity>` |
| `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` | Set `ARMED` synchronously in `armRecording()`; catch `start()` exceptions |
| `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt` | Add `releaseResources()` helper; guard `muxer.stop()` with `muxerStarted`; wrap `start()` in try/catch |
| `app/src/test/java/com/yotam/droneedge/video/FakeVideoSourceTest.kt` | New — 2 lifecycle tests |
| `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt` | Add 3 background auto-stop tests |
| `app/proguard-rules.pro` | Add TFLite keep rules |
| `app/build.gradle.kts` | Enable `isMinifyEnabled` + `signingConfig = debug` in release build type |

---

## Task 1: Manifest — Lock orientation and prevent config restarts

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Edit the `<activity>` tag**

Open `app/src/main/AndroidManifest.xml`. Find the `<activity android:name=".MainActivity"` tag and add the two new attributes:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="sensorLandscape"
    android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"
    android:theme="@style/Theme.DroneEdge" >
```

`sensorLandscape` allows both landscape orientations (for a tablet picked up from either side) but refuses portrait. `configChanges` tells Android to handle config changes in-process instead of recreating the Activity — without this, flipping the tablet kills a live session.

- [ ] **Step 2: Verify the build**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 3: Commit**

```bash
git checkout -b feature/pre-deployment-qa
git add app/src/main/AndroidManifest.xml
git commit -m "feat: lock MainActivity to sensorLandscape, handle config changes"
```

---

## Task 2: LiveViewModel — Set ARMED synchronously and handle recorder start failures

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt:274-289`

Currently `_recordingState.value = RecordingState.ARMED` is set on the IO thread after `rec.start()` completes. This creates two problems:
1. Background auto-stop tests are flaky (IO completion is not virtual-time controlled)
2. If `rec.start()` throws, the exception escapes silently and the ViewModel is left in a broken state

The fix: set `ARMED` immediately on the main thread (optimistic), then if the IO start fails, roll back to `IDLE` and surface an error. This is safe because `VideoSessionRecorder.onFrame()` already null-guards `encoder`, so frames received before `start()` completes are silently skipped.

- [ ] **Step 1: Rewrite `armRecording()`**

Replace the existing `armRecording()` body (lines 274–289 of `LiveViewModel.kt`):

```kotlin
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
    _recordingState.value = RecordingState.ARMED
    viewModelScope.launch(Dispatchers.IO) {
        try {
            rec.start(videoSource.width, videoSource.height, 30, getApplication())
        } catch (e: Exception) {
            timerJob?.cancel()
            timerJob = null
            _recordingElapsedMs.value = 0L
            _recordingState.value = RecordingState.IDLE
            _error.value = "Recording failed to start: ${e.message}"
            recorder = null
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run existing tests to confirm nothing broke**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "fix: set recording ARMED state synchronously; surface start() failures"
```

---

## Task 3: VideoSessionRecorder — Fix crash when stopped before first frame

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt`

**The bug:** `stop()` calls `muxer?.stop()` unconditionally. `MediaMuxer.stop()` throws `IllegalStateException` if the muxer was never started. The muxer is only started inside `drainEncoder()` when `INFO_OUTPUT_FORMAT_CHANGED` is received — which only happens after at least one encoded frame. If a session is armed and immediately stopped (zero frames), the app crashes.

**The fix:** Extract a `releaseResources()` helper that guards `muxer.stop()` with the existing `muxerStarted` flag, and wrap `start()` in try/catch so a partial setup is always cleaned up.

- [ ] **Step 1: Add `releaseResources()` after the `drainEncoder` function**

After the closing `}` of `drainEncoder()` (around line 242), add:

```kotlin
private fun releaseResources() {
    val enc = encoder
    if (enc != null) {
        runCatching { enc.stop() }
        runCatching { enc.release() }
        encoder = null
    }
    if (muxerStarted) runCatching { muxer?.stop() }
    runCatching { muxer?.release() }
    muxer = null
    runCatching { videoFd?.close() }
    videoFd = null
    runCatching { jsonWriter?.close() }
    jsonWriter = null
    muxerStarted = false
}
```

`runCatching` on each step ensures one failing release doesn't block the others.

- [ ] **Step 2: Replace the resource teardown in `stop()` with `releaseResources()`**

Replace the body of `stop()` (lines 174–207) with:

```kotlin
override suspend fun stop(): RecordingResult = withContext(Dispatchers.IO) {
    lock.withLock {
        stopped = true
        val enc = encoder
        if (enc != null) {
            val inputIdx = enc.dequeueInputBuffer(10_000L)
            if (inputIdx >= 0) {
                enc.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(endOfStream = true)
        }
        releaseResources()
        finalizeMediaStore()

        val durationMs = if (encoderFps > 0 && frameCount > 0)
            (frameCount.toLong() * 1_000L) / encoderFps else 0L
        RecordingResult(
            videoUri   = videoRowUri,
            jsonUri    = jsonRowUri,
            sessionId  = sessionName,
            frameCount = frameCount,
            durationMs = durationMs,
        )
    }
}
```

- [ ] **Step 3: Wrap `start()` in try/catch**

Replace the body of `start()` (the `withContext(Dispatchers.IO) { ... }` block, lines ~68–113) with:

```kotlin
override suspend fun start(width: Int, height: Int, fps: Int, context: Context) =
    withContext(Dispatchers.IO) {
        try {
            appContext    = context.applicationContext
            sessionName   = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            encodedWidth  = ((width  + 15) / 16) * 16
            encodedHeight = ((height + 15) / 16) * 16
            encoderFps    = fps
            frameCount    = 0
            stopped       = false
            videoTrackIndex = -1
            muxerStarted  = false

            val drawScale = encodedHeight / 1080f
            boxPaint.strokeWidth = (3f * drawScale).coerceAtLeast(2f)
            labelPaint.textSize  = (22f * drawScale).coerceAtLeast(12f)
            labelStripH          = labelPaint.textSize * 1.5f

            firstTimestampMs = System.currentTimeMillis()
            jsonWriter = openJsonWriter(context.applicationContext)
            jsonWriter?.appendLine("""{"sessionStart":$firstTimestampMs}""")

            val pfd = openVideoFile(context.applicationContext)
            videoFd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, encodedWidth, encodedHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
        } catch (e: Exception) {
            releaseResources()
            stopped = true
            throw e
        }
    }
```

- [ ] **Step 4: Build**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
git commit -m "fix: guard muxer.stop() with muxerStarted; add releaseResources() helper; wrap start() in try/catch"
```

---

## Task 4: FakeVideoSourceTest — lifecycle unit tests

**Files:**
- Create: `app/src/test/java/com/yotam/droneedge/video/FakeVideoSourceTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package com.yotam.droneedge.video

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVideoSourceTest {

    @Test
    fun `frames flow emits after start`() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeVideoSource()
        source.start()
        val frames = source.frames.take(3).toList()
        source.stop()
        assertEquals(3, frames.size)
        assertEquals(0L, frames[0].index)
        assertEquals(1L, frames[1].index)
        assertEquals(2L, frames[2].index)
    }

    @Test
    fun `stop halts the flow`() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeVideoSource()
        source.start()
        val collected = mutableListOf<VideoFrame>()
        val job = launch { source.frames.collect { collected.add(it) } }
        advanceTimeBy(100L)
        source.stop()
        advanceTimeBy(34L)   // allow the flow's current delay() to expire and exit the while loop
        job.join()
        assertTrue(collected.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the new tests**

```bash
./gradlew test --tests "com.yotam.droneedge.video.FakeVideoSourceTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/yotam/droneedge/video/FakeVideoSourceTest.kt
git commit -m "test: add FakeVideoSource lifecycle unit tests"
```

---

## Task 5: LiveViewModelTest — background auto-stop tests

**Files:**
- Modify: `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt`

These tests rely on Task 2's change (ARMED is now set synchronously), which makes `recordingState == ARMED` immediately after `vm.start()` in test context.

- [ ] **Step 1: Add 3 tests to the end of the `LiveViewModelTest` class**

Add inside the class body, after the last existing `@Test` method:

```kotlin
// ── Background auto-stop ──────────────────────────────────────────────────

@Test
fun `onAppBackgrounded when session not started is a no-op`() {
    vm.onAppBackgrounded()
    assertEquals(SessionState.IDLE, vm.sessionState.value)
}

@Test
fun `onAppForegrounded within 10s cancels the auto-stop`() = runTest(testDispatcher) {
    vm.start()
    assertEquals(RecordingState.ARMED, vm.recordingState.value)
    vm.onAppBackgrounded()
    advanceTimeBy(5_000L)           // half the grace period — timer not fired
    vm.onAppForegrounded()
    advanceTimeBy(10_000L)          // advance past the original deadline
    assertEquals(SessionState.RUNNING, vm.sessionState.value)
    vm.stop()
}

@Test
fun `onAppBackgrounded while armed stops full session after 10 seconds`() = runTest(testDispatcher) {
    vm.start()
    assertEquals(RecordingState.ARMED, vm.recordingState.value)
    vm.onAppBackgrounded()
    advanceTimeBy(10_001L)
    assertEquals(SessionState.IDLE, vm.sessionState.value)
}
```

The import `import kotlinx.coroutines.test.advanceTimeBy` is already present. No new imports needed.

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 90 tests pass (85 existing + 2 from Task 4 + 3 new).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt
git commit -m "test: add background auto-stop unit tests to LiveViewModelTest"
```

---

## Task 6: Release build — ProGuard TFLite rules and minification

**Files:**
- Modify: `app/proguard-rules.pro`
- Modify: `app/build.gradle.kts:26-32`

- [ ] **Step 1: Add TFLite keep rules to `app/proguard-rules.pro`**

Append to the end of the file:

```
# TFLite / LiteRT — keep all interpreter and native interop classes.
# R8 strips these by default and the app crashes at model load without them.
-keep class org.tensorflow.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-dontwarn org.tensorflow.**
```

- [ ] **Step 2: Enable minification in the release build type**

In `app/build.gradle.kts`, replace the existing `release { ... }` block (lines 26–32):

```kotlin
release {
    isMinifyEnabled = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
    signingConfig = signingConfigs.getByName("debug")
}
```

`signingConfigs.getByName("debug")` uses Android's built-in debug keystore. This is sufficient for sideloading and avoids needing a separate keystore file.

- [ ] **Step 3: Build the release APK**

```bash
./gradlew assembleRelease
```

Expected: `BUILD SUCCESSFUL`. The APK is at `app/build/outputs/apk/release/app-release.apk`.

If it fails with a missing `org.tensorflow` class error: the ProGuard rules were not applied. Verify `proguard-rules.pro` was saved and `isMinifyEnabled = true` is set.

- [ ] **Step 4: Run all tests one final time**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all 90 tests pass.

- [ ] **Step 5: Commit and push**

```bash
git add app/proguard-rules.pro app/build.gradle.kts
git commit -m "feat: enable release minification with TFLite ProGuard rules"
git push -u origin feature/pre-deployment-qa
```

Then open a PR: `gh pr create --base main --title "feat: pre-deployment QA and hardening"`

---

## Self-Review

**Spec coverage:**
- Area 1 (Manifest) → Task 1 ✅
- Area 2 (Camera permission) → Already implemented, noted at top ✅
- Area 3 (VideoSessionRecorder crash fix) → Tasks 2 + 3 ✅
- Area 4 (Unit tests) → Tasks 4 + 5 ✅
- Area 5 (Release build) → Task 6 ✅

**Placeholder scan:** None found.

**Type consistency:**
- `releaseResources()` defined and used within Task 3 only ✅
- `RecordingState.ARMED` set in Task 2's `armRecording()`, asserted in Task 5's tests ✅
- `testDispatcher` in Task 5 matches the existing field in `LiveViewModelTest` ✅
- `FakeVideoSourceTest` uses `UnconfinedTestDispatcher` consistently with `runTest` ✅
