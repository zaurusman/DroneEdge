# QoL Features — Recording & Gallery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add session naming (post-stop with MediaStore folder rename), gallery delete/rename, video thumbnails, live recording timer, and per-session detection count to DroneEdge.

**Architecture:** `recording/SessionRenamer.kt` holds two shared utilities: a pure name-sanitizer and a suspend rename function (MediaStore + filesystem). `LiveViewModel` gains `_pendingRename` to gate a post-stop naming dialog and a timer coroutine for elapsed time. `RecordingsViewModel` (new) takes over gallery data ownership from `produceState`, enabling delete/rename to trigger reloads. `RecordingEntry` gains `thumbnail` and `detectionCount` fields loaded alongside the MediaStore query.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidViewModel, Coroutines/Flow, MediaStore `ContentResolver.update` (folder rename), `ContentResolver.loadThumbnail` / `MediaStore.Video.Thumbnails` (thumbnails), `File.forEachLine` (detection count).

**Spec:** `docs/superpowers/specs/2026-06-01-qol-features-design.md`

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Modify | `app/src/main/java/com/yotam/droneedge/recording/RecordingResult.kt` | Add `sessionId: String` field |
| Create | `app/src/main/java/com/yotam/droneedge/recording/SessionRenamer.kt` | `sanitizeSessionName()` + `renameSession()` |
| Modify | `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt` | Populate `sessionId` in `stop()` result |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` | `_pendingRename`, `finalizeSessionName`, `skipNaming`, timer |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` | `NamingDialog` composable, timer in `RecButton` |
| Create | `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt` | Gallery data owner: load, rename, delete |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt` | Wire VM, new row layout, context menu |
| Modify | `app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt` | Add `sessionId` to `RecordingResult` return |
| Create | `app/src/test/java/com/yotam/droneedge/recording/SessionRenamerTest.kt` | Tests for pure functions |
| Modify | `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt` | Tests for `_pendingRename` and timer |

---

### Task 1: Branch + update `RecordingResult` and `FakeSessionRecorder`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/recording/RecordingResult.kt`
- Modify: `app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt`

- [ ] **Step 1: Create feature branch**

```bash
git checkout main && git pull
git checkout -b feature/qol-gallery-recording
```

- [ ] **Step 2: Add `sessionId` to `RecordingResult`**

Replace the entire file `app/src/main/java/com/yotam/droneedge/recording/RecordingResult.kt`:

```kotlin
package com.yotam.droneedge.recording

import android.net.Uri

data class RecordingResult(
    val videoUri:   Uri,
    val jsonUri:    Uri,
    val sessionId:  String,
    val frameCount: Int,
    val durationMs: Long,
)
```

- [ ] **Step 3: Update `FakeSessionRecorder` to supply `sessionId`**

In `app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt`, replace the `stop()` return:

```kotlin
override suspend fun stop(): RecordingResult {
    stopCalled = true
    return RecordingResult(
        videoUri   = Uri.EMPTY,
        jsonUri    = Uri.EMPTY,
        sessionId  = "session_fake",
        frameCount = framesReceived.size,
        durationMs = 0L,
    )
}
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL (the only compile error will be `VideoSessionRecorder.stop()` which still builds `RecordingResult` without `sessionId` — fix that in Task 2).

Actually `VideoSessionRecorder` won't compile until `sessionId` is added. Run:

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Fix any errors that are NOT in `VideoSessionRecorder.kt` (that file is fixed in Task 2). If compile fails elsewhere, investigate before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/RecordingResult.kt \
        app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt
git commit -m "feat: add sessionId field to RecordingResult"
```

---

### Task 2: Fix `VideoSessionRecorder` to populate `sessionId`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt:166-198`

- [ ] **Step 1: Populate `sessionId` in `stop()`**

Inside `VideoSessionRecorder.stop()`, find the `RecordingResult(...)` constructor call (around line 189) and add `sessionId = sessionName`:

```kotlin
RecordingResult(
    videoUri   = videoRowUri ?: Uri.EMPTY,
    jsonUri    = jsonRowUri  ?: Uri.EMPTY,
    sessionId  = sessionName,
    frameCount = frameCount,
    durationMs = durationMs,
)
```

- [ ] **Step 2: Verify compile and tests pass**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
git commit -m "feat: VideoSessionRecorder exposes sessionId in RecordingResult"
```

---

### Task 3: `SessionRenamer` — sanitizer and rename utility

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/recording/SessionRenamer.kt`
- Create: `app/src/test/java/com/yotam/droneedge/recording/SessionRenamerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/yotam/droneedge/recording/SessionRenamerTest.kt`:

```kotlin
package com.yotam.droneedge.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionRenamerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── sanitizeSessionName ───────────────────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertNull(sanitizeSessionName(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(sanitizeSessionName("   "))
    }

    @Test
    fun `normal name passes through unchanged`() {
        assertEquals("morning patrol", sanitizeSessionName("morning patrol"))
    }

    @Test
    fun `leading and trailing spaces are stripped`() {
        assertEquals("test", sanitizeSessionName("  test  "))
    }

    @Test
    fun `forward slashes replaced with dashes`() {
        assertEquals("a-b-c", sanitizeSessionName("a/b/c"))
    }

    @Test
    fun `name with only slashes becomes null after sanitize`() {
        assertNull(sanitizeSessionName("/"))
    }

    // ── countDetectionLines ───────────────────────────────────────────────────

    @Test
    fun `non-existent file returns -1`() {
        val file = tmp.newFolder().resolve("missing.json")
        assertEquals(-1, countDetectionLines(file))
    }

    @Test
    fun `file with three non-empty lines returns 3`() {
        val file = tmp.newFile("detections.json")
        file.writeText("{\"frameIndex\":0}\n{\"frameIndex\":1}\n{\"frameIndex\":2}\n")
        assertEquals(3, countDetectionLines(file))
    }

    @Test
    fun `blank lines are not counted`() {
        val file = tmp.newFile("blank.json")
        file.writeText("{\"frameIndex\":0}\n\n{\"frameIndex\":1}\n\n")
        assertEquals(2, countDetectionLines(file))
    }

    @Test
    fun `empty file returns 0`() {
        val file = tmp.newFile("empty.json")
        file.writeText("")
        assertEquals(0, countDetectionLines(file))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew test --tests "com.yotam.droneedge.recording.SessionRenamerTest" 2>&1 | tail -20
```

Expected: compilation error — `sanitizeSessionName` and `countDetectionLines` not found.

- [ ] **Step 3: Create `SessionRenamer.kt`**

Create `app/src/main/java/com/yotam/droneedge/recording/SessionRenamer.kt`:

```kotlin
package com.yotam.droneedge.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun sanitizeSessionName(input: String): String? {
    val trimmed = input.trim().replace('/', '-')
    return trimmed.ifEmpty { null }
}

internal fun countDetectionLines(file: File): Int {
    if (!file.exists()) return -1
    var count = 0
    file.forEachLine { if (it.isNotBlank()) count++ }
    return count
}

fun loadDetectionCount(context: Context, sessionName: String): Int =
    countDetectionLines(
        File(context.getExternalFilesDir(null), "recordings/$sessionName/detections.json")
    )

suspend fun renameSession(
    context: Context,
    videoUri: Uri,
    oldName: String,
    newName: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/$newName/")
            }
            context.contentResolver.update(videoUri, cv, null, null)
        }
        val oldDir = File(context.getExternalFilesDir(null), "recordings/$oldName")
        val newDir = File(context.getExternalFilesDir(null), "recordings/$newName")
        if (oldDir.exists()) oldDir.renameTo(newDir)
        true
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew test --tests "com.yotam.droneedge.recording.SessionRenamerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/recording/SessionRenamer.kt \
        app/src/test/java/com/yotam/droneedge/recording/SessionRenamerTest.kt
git commit -m "feat: add SessionRenamer with sanitizer, line counter, and rename utility"
```

---

### Task 4: `LiveViewModel` — `_pendingRename`, `finalizeSessionName`, `skipNaming`

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`
- Modify: `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Add the following tests to `LiveViewModelTest.kt` (inside the class, after the existing tests):

```kotlin
// ── Pending rename (post-stop naming) ─────────────────────────────────────

@Test
fun `initial pendingRename is null`() {
    assertNull(vm.pendingRename.value)
}

@Test
fun `skipNaming clears pendingRename and sets lastRecording`() = runTest(testDispatcher) {
    val fakeResult = com.yotam.droneedge.recording.RecordingResult(
        videoUri   = android.net.Uri.EMPTY,
        jsonUri    = android.net.Uri.EMPTY,
        sessionId  = "session_fake",
        frameCount = 0,
        durationMs = 0L,
    )
    vm.skipNaming(fakeResult)
    assertNull(vm.pendingRename.value)
    assertNotNull(vm.lastRecording.value)
}

@Test
fun `finalizeSessionName with blank name skips rename and sets lastRecording`() = runTest(testDispatcher) {
    val fakeResult = com.yotam.droneedge.recording.RecordingResult(
        videoUri   = android.net.Uri.EMPTY,
        jsonUri    = android.net.Uri.EMPTY,
        sessionId  = "session_fake",
        frameCount = 0,
        durationMs = 0L,
    )
    vm.finalizeSessionName(fakeResult, "   ")
    assertNull(vm.pendingRename.value)
    assertNotNull(vm.lastRecording.value)
}
```

Also add the required import at the top of `LiveViewModelTest.kt`:

```kotlin
import kotlinx.coroutines.test.runTest
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.LiveViewModelTest" 2>&1 | tail -20
```

Expected: compile error — `pendingRename`, `skipNaming`, `finalizeSessionName` not found.

- [ ] **Step 3: Implement in `LiveViewModel`**

In `LiveViewModel.kt`:

**3a.** Add the import at the top (alongside existing imports):

```kotlin
import com.yotam.droneedge.recording.renameSession
import com.yotam.droneedge.recording.sanitizeSessionName
```

**3b.** Add the new StateFlow next to `_lastRecording` (around line 89):

```kotlin
// ── Pending rename (awaiting user input before showing snackbar) ──────────
private val _pendingRename = MutableStateFlow<RecordingResult?>(null)
val pendingRename: StateFlow<RecordingResult?> = _pendingRename.asStateFlow()
```

**3c.** Modify `disarmRecording()` — change the line `_lastRecording.value = result` to `_pendingRename.value = result`:

```kotlin
fun disarmRecording() {
    if (!canDisarm(_recordingState.value)) return
    val rec = recorder ?: return
    _recordingState.value = RecordingState.FINALIZING
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { rec.stop() }
        _pendingRename.value = result          // was: _lastRecording.value = result
        _recordingState.value = RecordingState.IDLE
        recorder = null
    }
}
```

**3d.** Add `skipNaming` and `finalizeSessionName` after `clearLastRecording()`:

```kotlin
fun skipNaming(result: RecordingResult) {
    _pendingRename.value = null
    _lastRecording.value = result
}

fun finalizeSessionName(result: RecordingResult, name: String) {
    val sanitized = sanitizeSessionName(name)
    viewModelScope.launch(Dispatchers.IO) {
        if (sanitized != null && sanitized != result.sessionId) {
            renameSession(getApplication(), result.videoUri, result.sessionId, sanitized)
        }
        _pendingRename.value = null
        _lastRecording.value = result
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.LiveViewModelTest" 2>&1 | tail -20
```

Expected: all tests pass including the 3 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt \
        app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt
git commit -m "feat: LiveViewModel adds pendingRename state and session naming methods"
```

---

### Task 5: `LiveViewModel` — recording timer

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`
- Modify: `app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `LiveViewModelTest.kt`:

```kotlin
// ── Recording timer ───────────────────────────────────────────────────────

@Test
fun `initial recordingElapsedMs is zero`() {
    assertEquals(0L, vm.recordingElapsedMs.value)
}

@Test
fun `timer advances while recording is armed`() = runTest(testDispatcher) {
    vm.start()
    advanceTimeBy(2500L)
    assert(vm.recordingElapsedMs.value >= 2000L) {
        "Expected elapsed >= 2000ms, was ${vm.recordingElapsedMs.value}"
    }
}

@Test
fun `timer resets to zero on stop`() = runTest(testDispatcher) {
    vm.start()
    advanceTimeBy(3000L)
    vm.stop()
    assertEquals(0L, vm.recordingElapsedMs.value)
}
```

Add this import if not already present:

```kotlin
import kotlinx.coroutines.test.advanceTimeBy
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.LiveViewModelTest" 2>&1 | tail -20
```

Expected: compile error — `recordingElapsedMs` not found.

- [ ] **Step 3: Implement timer in `LiveViewModel`**

**3a.** Add the StateFlow and Job fields (after the existing `pipelineJob` declaration around line 109):

```kotlin
private val _recordingElapsedMs = MutableStateFlow(0L)
val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()
private var timerJob: Job? = null
```

**3b.** In `armRecording()`, start the timer after `_recordingState.value = RecordingState.ARMED`. Replace the entire `armRecording()` function:

```kotlin
fun armRecording() {
    if (!canArm(_sessionState.value, _recordingState.value)) return
    val rec = recorderFactory()
    recorder = rec
    viewModelScope.launch(Dispatchers.IO) {
        rec.start(videoSource.width, videoSource.height, 30, getApplication())
        _recordingState.value = RecordingState.ARMED
        _recordingElapsedMs.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _recordingElapsedMs.value += 1000L
            }
        }
    }
}
```

**3c.** Cancel the timer at the start of `disarmRecording()`. Replace the entire `disarmRecording()` function:

```kotlin
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
```

**3d.** Reset elapsed in `stop()`. Add two lines after `pipelineJob?.cancel()`:

```kotlin
timerJob?.cancel()
timerJob = null
_recordingElapsedMs.value = 0L
```

**3e.** Cancel in `onCleared()`. Add `timerJob?.cancel()` before `videoSource.stop()`:

```kotlin
override fun onCleared() {
    super.onCleared()
    timerJob?.cancel()
    if (canDisarm(_recordingState.value)) disarmRecording()
    videoSource.stop()
    pipelineJob?.cancel()
    tfliteDetector?.close()
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.LiveViewModelTest" 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt \
        app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt
git commit -m "feat: add live recording elapsed timer to LiveViewModel"
```

---

### Task 6: `LiveScreen` — naming dialog + timer in HUD

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Add `pendingRename` observation + `NamingDialog` call**

In `LiveScreen()`, add these lines after the existing `val lastRecording` observation (around line 105):

```kotlin
val pendingRename    by vm.pendingRename.collectAsStateWithLifecycle()
val recordingElapsed by vm.recordingElapsedMs.collectAsStateWithLifecycle()
```

Add the following import if not already present:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
```

Inside the `Box(modifier = Modifier.fillMaxSize())` block (the main content box in `LiveScreen`), add the dialog just before the closing `}` of the Box (after the `if (showSourceSheet)` block, around line 397):

```kotlin
// ── Post-recording naming dialog ──────────────────────────────────────────
pendingRename?.let { result ->
    NamingDialog(
        sessionId = result.sessionId,
        onConfirm = { name -> vm.finalizeSessionName(result, name) },
        onSkip    = { vm.skipNaming(result) },
    )
}
```

- [ ] **Step 2: Add timer display inside `RecButton`**

Pass `elapsedMs: Long` through `BottomBar`. Find the `BottomBar` composable signature (around line 402) and add the parameter:

```kotlin
@Composable
private fun BottomBar(
    modifier:       Modifier,
    sessionState:   SessionState,
    recordingState: RecordingState,
    elapsedMs:      Long,           // NEW
    sourceLabel:    String,
    onSourceClick:  () -> Unit,
    onGallery:      () -> Unit,
    onStart:        () -> Unit,
    onStop:         () -> Unit,
    onArmRecording: () -> Unit,
)
```

In `LiveScreen`, update the `BottomBar(...)` call (around line 318) to pass `elapsedMs = recordingElapsed`.

Pass `elapsedMs` into `RecButton`. Find the `RecButton` call inside `BottomBar` (around line 440):

```kotlin
RecButton(recordingState = recordingState, elapsedMs = elapsedMs, onArmRecording = onArmRecording)
```

Update the `RecButton` signature and its `ARMED` branch to show the timer:

```kotlin
@Composable
private fun RecButton(
    recordingState: RecordingState,
    elapsedMs:      Long,
    onArmRecording: () -> Unit,
) {
    // ... keep existing infiniteTransition + dotAlpha code unchanged ...

    when (recordingState) {
        RecordingState.IDLE -> OutlinedButton(
            onClick = onArmRecording,
            colors  = ButtonDefaults.outlinedButtonColors(contentColor = FieldRecRedLight),
            border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
        ) { Text("REC", fontSize = 12.sp) }

        RecordingState.ARMED -> {
            val timerStr = remember(elapsedMs) {
                "%d:%02d".format(elapsedMs / 60_000L, (elapsedMs / 1000L) % 60L)
            }
            OutlinedButton(
                onClick = {},
                colors  = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x331A0000),
                    contentColor   = FieldRecRedLight,
                ),
                border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
            ) {
                Text(
                    text       = "● REC  $timerStr",
                    fontSize   = 12.sp,
                    color      = FieldRecRedLight.copy(alpha = dotAlpha),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        RecordingState.FINALIZING -> OutlinedButton(
            onClick  = {},
            enabled  = false,
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = FieldTextMuted),
            border   = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
        ) { Text("Saving…", fontSize = 12.sp) }
    }
}
```

- [ ] **Step 3: Add `NamingDialog` composable at the bottom of `LiveScreen.kt`**

Add after the last composable in the file (after `CameraFrameDisplay` or wherever the file ends):

```kotlin
// ── Post-recording naming dialog ──────────────────────────────────────────

@Composable
private fun NamingDialog(
    sessionId: String,
    onConfirm: (String) -> Unit,
    onSkip:    () -> Unit,
) {
    val defaultName = remember(sessionId) {
        // Parse timestamp from sessionId "session_yyyyMMdd_HHmmss"
        runCatching {
            val ts = sessionId.removePrefix("session_")
            val parsed = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(ts)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(parsed!!)
        }.getOrDefault(sessionId)
    }
    var name by remember(sessionId) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Name this recording", color = FieldTextPrimary) },
        text  = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Session name") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = FieldAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Skip", color = FieldTextMuted)
            }
        },
        containerColor = FieldSurface,
    )
}
```

Add the missing import at the top of `LiveScreen.kt`:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import com.yotam.droneedge.ui.theme.FieldTextPrimary
```

(Only add imports not already present.)

- [ ] **Step 4: Verify compile**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: add post-recording naming dialog and elapsed timer to LiveScreen"
```

---

### Task 7: `RecordingsViewModel` — load and reload

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt`

- [ ] **Step 1: Move query functions to `RecordingsViewModel` visibility**

In `RecordingsScreen.kt`, change the three query functions from `private` to `internal`:

```kotlin
internal fun queryRecordings(context: Context): List<RecordingEntry> = ...
internal fun queryRecordingsMediaStore(context: Context): List<RecordingEntry> = ...
internal fun queryRecordingsFileSystem(): List<RecordingEntry> = ...
```

(Just remove the `private` modifier from each — do not change their bodies yet.)

- [ ] **Step 2: Create `RecordingsViewModel.kt`**

Create `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt`:

```kotlin
package com.yotam.droneedge.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yotam.droneedge.recording.renameSession
import com.yotam.droneedge.recording.sanitizeSessionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _recordings = MutableStateFlow<List<RecordingEntry>>(emptyList())
    val recordings: StateFlow<List<RecordingEntry>> = _recordings.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _recordings.value = withContext(Dispatchers.IO) {
                queryRecordings(getApplication())
            }
        }
    }

    fun rename(entry: RecordingEntry, newName: String) {
        val sanitized = sanitizeSessionName(newName) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = renameSession(getApplication(), entry.uri, entry.sessionName, sanitized)
            if (!ok) _error.value = "Rename failed"
            reload()
        }
    }

    fun delete(entry: RecordingEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.delete(entry.uri, null, null)
                File(getApplication<Application>().getExternalFilesDir(null),
                    "recordings/${entry.sessionName}").deleteRecursively()
            } catch (e: Exception) {
                _error.value = "Delete failed"
            }
            reload()
        }
    }

    fun clearError() { _error.value = null }
}
```

- [ ] **Step 3: Wire `RecordingsScreen` to use the ViewModel**

In `RecordingsScreen.kt`, replace `RecordingsScreen`. Use the existing `RecordingList` signature for now (new params added in Task 9):

```kotlin
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val vm = androidx.lifecycle.viewmodel.compose.viewModel<RecordingsViewModel>()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    if (playingEntry != null) {
        RecordingPlayer(entry = playingEntry!!, onBack = { playingEntry = null })
    } else {
        RecordingList(
            recordings = recordings,
            onSelect   = { playingEntry = it },
            onBack     = onBack,
        )
    }
}
```

Add missing imports at top of `RecordingsScreen.kt`:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: BUILD SUCCESSFUL. The rename/delete wiring is added in Task 9.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt \
        app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt
git commit -m "feat: introduce RecordingsViewModel to own gallery state"
```

---

### Task 8: `RecordingEntry` — thumbnail and detection count

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt`

- [ ] **Step 1: Write failing test for `countDetectionLines` (already passing from Task 3)**

No new tests needed — `countDetectionLines` was tested in Task 3. Verify:

```bash
./gradlew test --tests "com.yotam.droneedge.recording.SessionRenamerTest" 2>&1 | tail -5
```

Expected: all 8 tests pass.

- [ ] **Step 2: Add `thumbnail` and `detectionCount` to `RecordingEntry`**

In `RecordingsScreen.kt`, replace the `RecordingEntry` data class:

```kotlin
data class RecordingEntry(
    val uri:            Uri,
    val sessionName:    String,
    val durationMs:     Long,
    val dateMs:         Long,
    val thumbnail:      android.graphics.Bitmap?,
    val detectionCount: Int,   // -1 = sidecar not found
)
```

- [ ] **Step 3: Update `queryRecordingsMediaStore` to load thumbnail and detection count**

Replace `queryRecordingsMediaStore` with:

```kotlin
internal fun queryRecordingsMediaStore(context: Context): List<RecordingEntry> {
    val results    = mutableListOf<RecordingEntry>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.RELATIVE_PATH,
    )
    context.contentResolver.query(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("Movies/DroneEdge/%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val relativePath = cursor.getString(pathCol) ?: ""
            val sessionName  = relativePath.trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotEmpty() } ?: cursor.getString(nameCol)
            val id  = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id
            )
            val thumbnail = loadThumbnail(context, uri, id)
            val detCount  = com.yotam.droneedge.recording.loadDetectionCount(context, sessionName)
            results += RecordingEntry(
                uri            = uri,
                sessionName    = sessionName,
                durationMs     = cursor.getLong(durCol),
                dateMs         = cursor.getLong(dateCol) * 1000L,
                thumbnail      = thumbnail,
                detectionCount = detCount,
            )
        }
    }
    return results
}

private fun loadThumbnail(context: Context, uri: Uri, id: Long): android.graphics.Bitmap? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, android.util.Size(128, 72), null)
        }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver, id,
            MediaStore.Video.Thumbnails.MICRO_KIND, null
        )
    }
```

- [ ] **Step 4: Update `queryRecordingsFileSystem` to include the new fields**

Replace `queryRecordingsFileSystem`:

```kotlin
internal fun queryRecordingsFileSystem(): List<RecordingEntry> {
    // API < 29 path — no context available here; thumbnails/detection count skipped
    val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DroneEdge"
    )
    if (!root.exists()) return emptyList()
    return root.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { dir ->
            val mp4 = File(dir, "annotated.mp4")
            if (!mp4.exists()) return@mapNotNull null
            RecordingEntry(
                uri            = Uri.fromFile(mp4),
                sessionName    = dir.name,
                durationMs     = 0L,
                dateMs         = dir.lastModified(),
                thumbnail      = null,
                detectionCount = -1,
            )
        }
        ?: emptyList()
}
```

- [ ] **Step 5: Verify compile**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: BUILD SUCCESSFUL (the `RecordingList` signature change is handled in Task 9).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt
git commit -m "feat: RecordingEntry gains thumbnail and detectionCount fields"
```

---

### Task 9: Gallery row UI — Option A layout (thumbnail + stats)

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt`

- [ ] **Step 1: Add stub params to `RecordingRow` (keeps existing body intact)**

Find `RecordingRow` (currently `private fun RecordingRow(entry, onClick)`) and update only its signature:

```kotlin
@Composable
private fun RecordingRow(
    entry:    RecordingEntry,
    onClick:  () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // existing body unchanged for now
```

This makes the signature ready before the callers reference the new params.

- [ ] **Step 2: Update `RecordingList` and `RecordingsScreen()` to wire the new params**

Update `RecordingList` signature and update `RecordingsScreen()` at the same time (both in `RecordingsScreen.kt`):

Replace `RecordingsScreen()`:

```kotlin
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val vm = androidx.lifecycle.viewmodel.compose.viewModel<RecordingsViewModel>()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val error      by vm.error.collectAsStateWithLifecycle()
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    if (playingEntry != null) {
        RecordingPlayer(entry = playingEntry!!, onBack = { playingEntry = null })
    } else {
        RecordingList(
            recordings   = recordings,
            error        = error,
            onSelect     = { playingEntry = it },
            onBack       = onBack,
            onRename     = { entry, name -> vm.rename(entry, name) },
            onDelete     = { entry -> vm.delete(entry) },
            onClearError = { vm.clearError() },
        )
    }
}
```

Update `RecordingList` signature and wiring:

```kotlin
@Composable
private fun RecordingList(
    recordings:   List<RecordingEntry>,
    error:        String?,
    onSelect:     (RecordingEntry) -> Unit,
    onBack:       () -> Unit,
    onRename:     (RecordingEntry, String) -> Unit,
    onDelete:     (RecordingEntry) -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FieldBackground)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .background(FieldSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("←", color = FieldAccent, fontSize = 20.sp)
            }
            Text(
                text          = "GALLERY",
                color         = FieldTextPrimary,
                fontWeight    = FontWeight.Bold,
                fontSize      = 15.sp,
                letterSpacing = 1.sp,
            )
        }

        if (error != null) {
            androidx.compose.material3.Snackbar(
                modifier = Modifier.padding(8.dp),
                action   = { androidx.compose.material3.TextButton(onClick = onClearError) { Text("Dismiss") } },
            ) { Text(error) }
        }

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings found.", color = FieldTextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings) { entry ->
                    RecordingRow(
                        entry    = entry,
                        onClick  = { onSelect(entry) },
                        onRename = { name -> onRename(entry, name) },
                        onDelete = { onDelete(entry) },
                    )
                    HorizontalDivider(color = FieldBorder)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Replace `RecordingRow` with Option A layout**

Replace the entire `RecordingRow` composable:

```kotlin
@Composable
private fun RecordingRow(
    entry:    RecordingEntry,
    onClick:  () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(entry.dateMs) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(entry.dateMs))
    }
    val durationStr = remember(entry.durationMs) {
        val s = entry.durationMs / 1000
        "%d:%02d".format(s / 60, s % 60)
    }
    val detStr = remember(entry.detectionCount) {
        if (entry.detectionCount >= 0) "${entry.detectionCount} det" else "—"
    }

    var showMenu   by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick     = onClick,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // Thumbnail
            if (entry.thumbnail != null) {
                androidx.compose.foundation.Image(
                    bitmap             = entry.thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier           = Modifier
                        .size(width = 72.dp, height = 44.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
                    contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(
                    modifier          = Modifier
                        .size(width = 72.dp, height = 44.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment  = Alignment.Center,
                ) {
                    Text("▶", color = Color(0xFF555555), fontSize = 14.sp)
                }
            }

            // Name + date
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = entry.sessionName,
                    color      = FieldTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 13.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(text = dateStr, color = FieldTextSecondary, fontSize = 11.sp)
            }

            // Duration + detection count
            Column(horizontalAlignment = Alignment.End) {
                Text(text = durationStr, color = FieldTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text     = detStr,
                    color    = if (entry.detectionCount > 0) FieldAccent.copy(alpha = 0.8f) else FieldTextMuted,
                    fontSize = 10.sp,
                )
            }
        }

        // Context menu
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text    = { Text("Rename") },
                onClick = { showMenu = false; showRename = true },
            )
            DropdownMenuItem(
                text    = { Text("Delete", color = Color.Red) },
                onClick = { showMenu = false; showDelete = true },
            )
        }
    }

    // Rename dialog
    if (showRename) {
        var nameInput by remember { mutableStateOf(entry.sessionName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title            = { Text("Rename recording") },
            text             = {
                OutlinedTextField(
                    value         = nameInput,
                    onValueChange = { nameInput = it },
                    label         = { Text("Session name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(nameInput); showRename = false }) {
                    Text("Save", color = FieldAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
            containerColor = FieldSurface,
        )
    }

    // Delete confirmation dialog
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title            = { Text("Delete recording?") },
            text             = { Text("\"${entry.sessionName}\" will be permanently deleted.") },
            confirmButton    = {
                TextButton(onClick = { onDelete(); showDelete = false }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton    = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
            containerColor   = FieldSurface,
        )
    }
}
```

Add these imports to `RecordingsScreen.kt` (only those not already present):

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
```

`combinedClickable` requires the `@OptIn(ExperimentalFoundationApi::class)` annotation on the composable or file-level opt-in. Add at the top of `RecordingRow`:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(...) {
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt
git commit -m "feat: gallery row shows thumbnail, detection count, and long-press rename/delete menu"
```

---

### Task 10: Build gate + PR

- [ ] **Step 1: Full build and test**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Review changed files**

```bash
git diff main --name-only
```

Expected files:
```
app/src/main/java/com/yotam/droneedge/recording/RecordingResult.kt
app/src/main/java/com/yotam/droneedge/recording/SessionRenamer.kt
app/src/main/java/com/yotam/droneedge/recording/VideoSessionRecorder.kt
app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsViewModel.kt
app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt
app/src/test/java/com/yotam/droneedge/recording/FakeSessionRecorder.kt
app/src/test/java/com/yotam/droneedge/recording/SessionRenamerTest.kt
app/src/test/java/com/yotam/droneedge/ui/live/LiveViewModelTest.kt
```

- [ ] **Step 3: Open PR**

```bash
gh pr create \
  --base main \
  --title "feat: QoL features — session naming, gallery delete/rename, thumbnails, timer, detection count" \
  --body "$(cat <<'EOF'
## Summary
- Session naming dialog appears after stopping a recording; name becomes the actual MediaStore folder name visible in Android Gallery and Files app
- Gallery supports rename and delete via long-press context menu
- Gallery rows show a video thumbnail and detection count alongside the existing name/date/duration
- Live screen HUD shows elapsed recording time while armed (e.g. ● REC 1:23)

## Test plan
- [ ] Start a recording, stop it, enter a name, verify folder appears with that name in Files app
- [ ] Start a recording, stop it, tap Skip, verify auto-generated name is used
- [ ] Long-press a gallery row, rename it, verify gallery updates and Files app folder renamed
- [ ] Long-press a gallery row, delete it, confirm dialog, verify it disappears from gallery
- [ ] Verify thumbnails appear for recorded sessions
- [ ] Verify detection count shows "N det" for sessions with a detections.json sidecar
- [ ] Verify timer counts up while recording is armed and resets on stop
- [ ] `./gradlew test` — all unit tests pass

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
