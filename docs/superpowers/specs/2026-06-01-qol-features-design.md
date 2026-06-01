# QoL Features Design

**Date:** 2026-06-01  
**Status:** Approved

## Overview

Five quality-of-life improvements to the recording and gallery experience:

1. Session naming (after stop, with MediaStore folder rename)
2. Delete recordings from gallery
3. Video thumbnails in gallery
4. Live recording timer on HUD
5. Detection count per session in gallery

---

## Feature 1 — Session Naming

### User flow

When the user stops a recording, a naming dialog appears before the snackbar fires. The dialog pre-fills with a formatted date/time string (e.g. `"2024-06-01 08:42"`) derived from the `sessionId` timestamp. The user can edit it or accept the default.

- **Save**: apply the name via MediaStore rename + filesystem rename, then show snackbar.
- **Skip**: keep the auto-generated `sessionId` as the display name (e.g. `session_20240601_084200`), no rename. The gallery will show this string.

### Name as folder

The session name is the actual folder name on the filesystem (`Movies/DroneEdge/<name>/`). This means it is visible in the Android Gallery and Files app without any additional metadata. A metadata-only approach was explicitly rejected for this reason.

### Rename mechanism

Two operations run together after the user confirms:

1. `ContentResolver.update(videoUri, ContentValues { RELATIVE_PATH = "Movies/DroneEdge/$name/" }, null, null)` — moves the MediaStore video row to the new folder path.
2. `File(externalFilesDir, "recordings/$sessionId").renameTo(File(externalFilesDir, "recordings/$name"))` — renames the JSON sidecar directory.

Both operations are dispatched on `Dispatchers.IO`.

### Name validation

- Strip leading/trailing whitespace.
- Reject empty string — fall back to auto-generated name silently.
- Replace `/` with `-` to prevent path traversal.
- No length limit enforced in the UI (Android filesystem limit applies naturally).

### ViewModel changes

`LiveViewModel` adds:

```
_pendingRename: MutableStateFlow<RecordingResult?> = null
val pendingRename: StateFlow<RecordingResult?>
```

`disarmRecording()` sets `_pendingRename` instead of `_lastRecording` when the recording finishes. A new `fun finalizeSessionName(result: RecordingResult, name: String)` method performs the rename and then sets `_lastRecording` to fire the existing snackbar.

`RecordingResult` gains a `sessionId: String` field — the auto-generated folder name used as a stable key for the rename.

### Gallery rename

Rename from gallery uses the same `ContentResolver.update()` + `File.renameTo()` pattern, triggered by the context menu (see Feature 2).

---

## Feature 2 — Delete Recordings

### Interaction

Long-press on a gallery row opens a `DropdownMenu` with two items: **Rename** and **Delete**.

- **Rename** shows the same naming `AlertDialog` used post-stop.
- **Delete** shows a confirmation `AlertDialog` ("Delete this recording? This cannot be undone.") before proceeding.

### Delete mechanism

1. `ContentResolver.delete(entry.uri, null, null)` — removes the MediaStore video row and the underlying file.
2. `File(externalFilesDir, "recordings/${entry.sessionName}").deleteRecursively()` — removes the JSON sidecar directory.
3. Gallery list reloads.

Both operations on `Dispatchers.IO`.

---

## Feature 3 — Video Thumbnails

### Layout

Gallery row layout (Option A, approved):

```
[ 72×44 thumb ] [ Name (bold)          ] [ 1:23      ]
                [ 2024-06-01  08:42    ] [ 47 det    ]
```

Thumbnail is a rounded-corner image view. If no thumbnail is available (old recording, load failure), a dark placeholder with a play-triangle icon is shown.

### Loading

- API 29+: `context.contentResolver.loadThumbnail(uri, Size(128, 72), null)` — system-managed, cached by MediaStore.
- API < 29: `MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MICRO_KIND, null)`.

Thumbnails are loaded on `Dispatchers.IO` as part of the gallery query, stored as `Bitmap?` on `RecordingEntry`. No additional caching layer is needed — `RecordingsViewModel` retains the list across recompositions.

---

## Feature 4 — Live Recording Timer

### Behavior

While `RecordingState == ARMED`, the live screen HUD shows a pulsing red dot and elapsed time: `"● REC 1:23"`. The timer resets to zero when a new recording starts. It stops updating (but remains visible) while `RecordingState == FINALIZING`.

### Implementation

`LiveViewModel` adds:

```
_recordingElapsedMs: MutableStateFlow<Long>(0L)
val recordingElapsedMs: StateFlow<Long>
```

When `RecordingState` transitions to `ARMED`, a coroutine launches in `viewModelScope`:

```kotlin
while (true) {
    delay(1000L)
    _recordingElapsedMs.value += 1000L
}
```

The coroutine is stored in a `Job` reference and cancelled when recording returns to `IDLE`. `_recordingElapsedMs` resets to `0L` at arm time.

`LiveScreen` formats the value as `"%d:%02d".format(ms / 60000, (ms / 1000) % 60)` and renders it using the existing animated dot already present in the file.

---

## Feature 5 — Detection Count in Gallery

### Display

The detection count appears below the duration in the right column of each gallery row (see Feature 3 layout). Format: `"47 det"`. If the JSON sidecar is absent or unreadable, show `"—"`.

### Loading

`RecordingEntry` gains `detectionCount: Int` (value `-1` = unavailable).

During gallery query, for each entry the loader attempts to open `getExternalFilesDir("recordings/${sessionName}/detections.json")` and count non-empty lines. This is a line count — no JSON parsing needed, as each detection event is one line. Performed on `Dispatchers.IO` alongside the MediaStore query.

---

## Data Model Changes

### `RecordingResult` (recording package)

```kotlin
data class RecordingResult(
    val videoUri:   Uri,
    val jsonUri:    Uri,
    val sessionId:  String,   // NEW — auto-generated folder name, stable rename key
    val frameCount: Int,
    val durationMs: Long,
)
```

### `RecordingEntry` (ui/recordings package)

```kotlin
data class RecordingEntry(
    val uri:            Uri,
    val sessionName:    String,
    val durationMs:     Long,
    val dateMs:         Long,
    val thumbnail:      Bitmap?,  // NEW
    val detectionCount: Int,      // NEW — -1 if unavailable
)
```

---

## New: `RecordingsViewModel`

`RecordingsScreen` currently loads recordings via `produceState` in the composable. This moves into a `RecordingsViewModel` so that delete and rename can trigger a controlled reload.

```kotlin
class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    val recordings: StateFlow<List<RecordingEntry>>
    fun reload()
    fun rename(entry: RecordingEntry, newName: String)
    fun delete(entry: RecordingEntry)
}
```

`RecordingsScreen` receives the VM via `viewModel()`.

---

## Error Handling

- MediaStore rename failure: log the error, keep original name, show a brief toast.
- JSON directory rename failure: non-fatal — log only; the video is still correctly renamed.
- Thumbnail load failure: show placeholder silently.
- Detection count read failure: show `"—"` silently.
- Delete failure: show a snackbar error to the user.

---

## Testing

- Unit test `finalizeSessionName()`: verify ContentValues are built correctly, verify JSON dir rename is called with correct paths.
- Unit test name validation: empty string, whitespace-only, string containing `/`.
- Unit test detection count loader: file present with N lines, file absent, file with empty lines.
- Unit test recording elapsed timer: verify it starts at 0, increments each second, resets on re-arm.
- Existing `RecordingStateTransitionTest` should continue to pass unchanged.
