# UI/UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the Field Orange visual theme, restructure the Live Screen controls (source/model sheets, state-switched bottom bar, semi-transparent HUD, breathing REC dot), add a model selection screen on every launch, and rename Recordings → Gallery throughout.

**Architecture:** No new screens are added to the navigation graph — the existing `rememberSaveable` state switching in `MainActivity` gains a third state for the model selection screen. Source and model selection are extracted into `ModalBottomSheet` composables that live alongside `LiveScreen`. All theme tokens are centralised in `Color.kt` and consumed by raw `Color(…)` references in the UI files (Material3 `colorScheme` covers the primary action colour only).

**Tech Stack:** Jetpack Compose Material3 (`ModalBottomSheet`, `InfiniteTransition`), `SharedPreferences` for model persistence. No new Gradle dependencies.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create branch | — | `feature/ui-ux-redesign` |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/theme/Color.kt` | Field Orange palette tokens |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/theme/Theme.kt` | Always-dark scheme, disable dynamic color |
| Create | `app/src/main/java/com/yotam/droneedge/ui/live/ModelSelectionScreen.kt` | Full-screen model picker shown on every cold start |
| Create | `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt` | `ModalBottomSheet` listing all video sources |
| Create | `app/src/main/java/com/yotam/droneedge/ui/live/ModelSheet.kt` | `ModalBottomSheet` listing detector models |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` | State-switched bottom bar, semi-transparent HUD, breathing REC, orange boxes, integrate sheets |
| Modify | `app/src/main/java/com/yotam/droneedge/MainActivity.kt` | Add ModelSelectionScreen to nav, SharedPreferences persistence |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt` | Rename "Recordings"→"Gallery", Field Orange styling |
| Create | `app/src/test/java/com/yotam/droneedge/ui/live/DetectorModeTest.kt` | Verify enum round-trip + fallback logic used by persistence |

---

## Task 1: Create feature branch

**Files:** none (git only)

- [ ] **Step 1: Create and switch to branch**

```bash
git checkout main && git pull && git checkout -b feature/ui-ux-redesign
```

Expected: `Switched to a new branch 'feature/ui-ux-redesign'`

---

## Task 2: Field Orange theme

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/theme/Theme.kt`

- [ ] **Step 1: Replace Color.kt with Field Orange tokens**

Replace the entire file content:

```kotlin
package com.yotam.droneedge.ui.theme

import androidx.compose.ui.graphics.Color

val FieldBackground       = Color(0xFF0A0A0A)
val FieldSurface          = Color(0xFF111111)
val FieldSurfaceElevated  = Color(0xFF161616)
val FieldBorder           = Color(0xFF1F2937)
val FieldAccent           = Color(0xFFF97316)
val FieldTextPrimary      = Color(0xFFE5E7EB)
val FieldTextSecondary    = Color(0xFF9CA3AF)
val FieldTextMuted        = Color(0xFF6B7280)
val FieldRecRed           = Color(0xFFDC2626)
val FieldRecRedLight      = Color(0xFFF87171)
```

- [ ] **Step 2: Replace Theme.kt — always dark, disable dynamic color**

Replace the entire file content:

```kotlin
package com.yotam.droneedge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DroneEdgeColorScheme = darkColorScheme(
    primary          = FieldAccent,
    onPrimary        = Color.Black,
    background       = FieldBackground,
    onBackground     = FieldTextPrimary,
    surface          = FieldSurface,
    onSurface        = FieldTextPrimary,
    error            = FieldRecRed,
    onError          = Color.White,
    surfaceVariant   = FieldSurfaceElevated,
    onSurfaceVariant = FieldTextSecondary,
    outline          = FieldBorder,
)

@Composable
fun DroneEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroneEdgeColorScheme,
        typography  = Typography,
        content     = content,
    )
}
```

- [ ] **Step 3: Build to verify theme compiles**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/theme/Color.kt \
        app/src/main/java/com/yotam/droneedge/ui/theme/Theme.kt
git commit -m "feat: apply Field Orange theme tokens, always-dark scheme"
```

---

## Task 3: DetectorMode persistence test + ModelSelectionScreen

**Files:**
- Create: `app/src/test/java/com/yotam/droneedge/ui/live/DetectorModeTest.kt`
- Create: `app/src/main/java/com/yotam/droneedge/ui/live/ModelSelectionScreen.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/MainActivity.kt`

- [ ] **Step 1: Write failing tests for DetectorMode persistence contract**

Create `app/src/test/java/com/yotam/droneedge/ui/live/DetectorModeTest.kt`:

```kotlin
package com.yotam.droneedge.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorModeTest {

    @Test
    fun `all DetectorMode values round-trip through name`() {
        DetectorMode.entries.forEach { mode ->
            assertEquals(mode, DetectorMode.valueOf(mode.name))
        }
    }

    @Test
    fun `unknown stored name falls back to FAKE`() {
        val stored = "UNKNOWN_FUTURE_MODEL"
        val result = runCatching { DetectorMode.valueOf(stored) }.getOrDefault(DetectorMode.FAKE)
        assertEquals(DetectorMode.FAKE, result)
    }

    @Test
    fun `empty stored name falls back to FAKE`() {
        val result = runCatching { DetectorMode.valueOf("") }.getOrDefault(DetectorMode.FAKE)
        assertEquals(DetectorMode.FAKE, result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (DetectorMode.entries not yet present)**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.DetectorModeTest"
```

Expected: if `DetectorMode` already uses `enum class` the tests pass immediately — that is fine, proceed. If they fail for compilation reasons, check the enum file.

- [ ] **Step 3: Verify DetectorMode uses enum class (entries is available in Kotlin 1.9+)**

Open `app/src/main/java/com/yotam/droneedge/ui/live/DetectorMode.kt` — confirm it is `enum class`. If the project uses Kotlin < 1.9, replace `DetectorMode.entries` with `DetectorMode.values()` in the test.

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew test --tests "com.yotam.droneedge.ui.live.DetectorModeTest"
```

Expected: `3 tests completed, 0 failed`

- [ ] **Step 5: Create ModelSelectionScreen.kt**

```kotlin
package com.yotam.droneedge.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurfaceElevated
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary

@Composable
fun ModelSelectionScreen(
    initialMode: DetectorMode,
    isTfliteAvailable: Boolean,
    onConfirm: (DetectorMode) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initialMode) }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(FieldBackground)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text       = "DRONEEDGE",
            color      = FieldAccent,
            fontSize   = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text          = "SELECT DETECTION MODEL",
            color         = FieldTextMuted,
            fontSize      = 11.sp,
            letterSpacing = 1.5.sp,
        )

        Spacer(Modifier.height(40.dp))

        ModelOption(
            title       = "Fake Detector",
            description = "Generates random bounding boxes. Use for UI testing without a model file.",
            selected    = selected == DetectorMode.FAKE,
            enabled     = true,
            onClick     = { selected = DetectorMode.FAKE },
        )

        Spacer(Modifier.height(10.dp))

        ModelOption(
            title       = "TFLite — SSD MobileNet",
            description = if (isTfliteAvailable)
                "On-device object detection. Runs inference off the main thread."
            else
                "Model not found — detect.tflite asset is missing.",
            selected    = selected == DetectorMode.TFLITE,
            enabled     = isTfliteAvailable,
            onClick     = { if (isTfliteAvailable) selected = DetectorMode.TFLITE },
        )

        Spacer(Modifier.height(40.dp))

        Button(
            onClick  = { onConfirm(selected) },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor = FieldAccent,
                contentColor   = Color.Black,
            ),
        ) {
            Text(
                text       = "CONFIRM",
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ModelOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> FieldAccent
        else     -> FieldBorder
    }
    val bgColor = when {
        selected -> Color(0xFF1A1200)
        else     -> FieldSurfaceElevated
    }
    val titleColor = when {
        !enabled -> FieldTextMuted
        selected -> FieldAccent
        else     -> FieldTextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text = description, color = FieldTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (selected) {
            Spacer(Modifier.size(12.dp))
            Spacer(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(FieldAccent),
            )
        }
    }
}
```

- [ ] **Step 6: Update MainActivity.kt — add model selection nav + SharedPreferences persistence**

Replace the entire file:

```kotlin
package com.yotam.droneedge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.yotam.droneedge.ui.live.DetectorMode
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.live.LiveViewModel
import com.yotam.droneedge.ui.live.ModelSelectionScreen
import com.yotam.droneedge.ui.recordings.RecordingsScreen
import com.yotam.droneedge.ui.theme.DroneEdgeTheme

private const val PREFS_NAME     = "droneedge_prefs"
private const val KEY_DETECTOR   = "detector_mode"

class MainActivity : ComponentActivity() {

    private val vm: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.let { vm.handleUsbLaunchIntent(it, this) }
        setContent {
            DroneEdgeTheme {
                var showModelSelection by rememberSaveable { mutableStateOf(true) }
                var showGallery        by rememberSaveable { mutableStateOf(false) }

                when {
                    showModelSelection -> ModelSelectionScreen(
                        initialMode        = loadDetectorMode(),
                        isTfliteAvailable  = isTfliteAvailable(),
                        onConfirm          = { mode ->
                            vm.setDetectorMode(mode, this@MainActivity)
                            saveDetectorMode(mode)
                            showModelSelection = false
                        },
                    )
                    showGallery -> {
                        BackHandler { showGallery = false }
                        RecordingsScreen(onBack = { showGallery = false })
                    }
                    else -> LiveScreen(
                        vm           = vm,
                        onGallery    = { showGallery = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleUsbLaunchIntent(intent, this)
    }

    private fun loadDetectorMode(): DetectorMode {
        val stored = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DETECTOR, DetectorMode.FAKE.name) ?: DetectorMode.FAKE.name
        return runCatching { DetectorMode.valueOf(stored) }.getOrDefault(DetectorMode.FAKE)
    }

    private fun saveDetectorMode(mode: DetectorMode) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DETECTOR, mode.name)
            .apply()
    }

    private fun isTfliteAvailable(): Boolean = runCatching {
        assets.open("detect.tflite").close()
    }.isSuccess
}
```

Note: `LiveScreen` now receives `onGallery` instead of `onRecordings`. The signature change will cause a compile error until LiveScreen is updated in Task 6. That is expected — proceed to Task 4 and Task 5 first, then fix LiveScreen in Task 6.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/yotam/droneedge/ui/live/DetectorModeTest.kt \
        app/src/main/java/com/yotam/droneedge/ui/live/ModelSelectionScreen.kt \
        app/src/main/java/com/yotam/droneedge/MainActivity.kt
git commit -m "feat: add model selection screen and SharedPreferences persistence"
```

---

## Task 4: SourceSheet composable

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt`

- [ ] **Step 1: Create SourceSheet.kt**

```kotlin
package com.yotam.droneedge.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurfaceElevated
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary

enum class SourceChoice { CAMERA, USB, FILE, FAKE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(
    activeChoice: SourceChoice?,
    onSelect: (SourceChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = FieldSurfaceElevated,
        tonalElevation   = 0.dp,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text          = "SELECT SOURCE",
                color         = FieldTextMuted,
                fontSize      = 10.sp,
                letterSpacing = 1.5.sp,
                modifier      = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            SourceRow(
                label    = "Camera (back)",
                active   = activeChoice == SourceChoice.CAMERA,
                enabled  = true,
                onClick  = { onSelect(SourceChoice.CAMERA) },
            )
            SourceRow(
                label    = "USB / UVC",
                active   = activeChoice == SourceChoice.USB,
                enabled  = true,
                onClick  = { onSelect(SourceChoice.USB) },
            )
            SourceRow(
                label    = "Video File",
                active   = activeChoice == SourceChoice.FILE,
                enabled  = true,
                onClick  = { onSelect(SourceChoice.FILE) },
            )
            SourceRow(
                label    = "DJI Goggles",
                active   = false,
                enabled  = false,
                suffix   = "coming soon",
                onClick  = {},
            )
            SourceRow(
                label    = "Fake (dev)",
                active   = activeChoice == SourceChoice.FAKE,
                enabled  = true,
                onClick  = { onSelect(SourceChoice.FAKE) },
            )
        }
    }
}

@Composable
private fun SourceRow(
    label:   String,
    active:  Boolean,
    enabled: Boolean,
    suffix:  String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) FieldSurfaceElevated else FieldSurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = label,
            color      = when {
                !enabled -> FieldTextMuted
                active   -> FieldAccent
                else     -> FieldTextPrimary
            },
            fontSize   = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier   = Modifier.weight(1f),
        )
        if (suffix != null) {
            Text(text = suffix, color = FieldTextMuted, fontSize = 11.sp)
        }
        if (active) {
            Text(text = "✓", color = FieldAccent, fontSize = 13.sp)
        }
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FieldBorder)
            .padding(horizontal = 20.dp),
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt
git commit -m "feat: add SourceSheet bottom sheet composable"
```

---

## Task 5: ModelSheet composable

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/ui/live/ModelSheet.kt`

- [ ] **Step 1: Create ModelSheet.kt**

```kotlin
package com.yotam.droneedge.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurfaceElevated
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    currentMode:       DetectorMode,
    isTfliteAvailable: Boolean,
    onSelect:          (DetectorMode) -> Unit,
    onDismiss:         () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = FieldSurfaceElevated,
        tonalElevation   = 0.dp,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text          = "SELECT MODEL",
                color         = FieldTextMuted,
                fontSize      = 10.sp,
                letterSpacing = 1.5.sp,
                modifier      = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            ModelRow(
                label   = "Fake Detector",
                active  = currentMode == DetectorMode.FAKE,
                enabled = true,
                onClick = { onSelect(DetectorMode.FAKE) },
            )
            ModelRow(
                label   = "TFLite — SSD MobileNet",
                active  = currentMode == DetectorMode.TFLITE,
                enabled = isTfliteAvailable,
                suffix  = if (!isTfliteAvailable) "model not found" else null,
                onClick = { if (isTfliteAvailable) onSelect(DetectorMode.TFLITE) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    label:   String,
    active:  Boolean,
    enabled: Boolean,
    suffix:  String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = label,
            color      = when {
                !enabled -> FieldTextMuted
                active   -> FieldAccent
                else     -> FieldTextPrimary
            },
            fontSize   = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier   = Modifier.weight(1f),
        )
        if (suffix != null) {
            Text(text = suffix, color = FieldTextMuted, fontSize = 11.sp)
        }
        if (active) {
            Text(text = "✓", color = FieldAccent, fontSize = 13.sp)
        }
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FieldBorder),
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/ModelSheet.kt
git commit -m "feat: add ModelSheet bottom sheet composable"
```

---

## Task 6: LiveScreen restructure

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

This task replaces LiveScreen.kt entirely. Key changes:
- Signature: `onRecordings` → `onGallery` (fixes the compile error introduced in Task 3)
- Bottom bar state-switched: IDLE shows Source + Model + Gallery + START; RUNNING shows REC + STOP
- Semi-transparent HUD (no border box, ~40% opacity background)
- Breathing REC dot via `InfiniteTransition`
- Orange bounding boxes (`FieldAccent` replaces cyan)
- Integrates `SourceSheet` and `ModelSheet`
- Active source label derived from ViewModel state

- [ ] **Step 1: Replace LiveScreen.kt**

```kotlin
package com.yotam.droneedge.ui.live

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldRecRed
import com.yotam.droneedge.ui.theme.FieldRecRedLight
import com.yotam.droneedge.ui.theme.FieldSurface
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextSecondary
import com.yotam.droneedge.video.VideoFrame

private const val ACTION_USB_PERMISSION = "com.yotam.droneedge.USB_PERMISSION"

@Composable
fun LiveScreen(
    vm:       LiveViewModel = viewModel(),
    onGallery: () -> Unit   = {},
) {
    val sessionState   by vm.sessionState.collectAsStateWithLifecycle()
    val detections     by vm.detections.collectAsStateWithLifecycle()
    val previewFps     by vm.previewFps.collectAsStateWithLifecycle()
    val inferenceFps   by vm.inferenceFps.collectAsStateWithLifecycle()
    val videoUri       by vm.videoUri.collectAsStateWithLifecycle()
    val detectorMode   by vm.detectorMode.collectAsStateWithLifecycle()
    val error          by vm.error.collectAsStateWithLifecycle()
    val recordingState by vm.recordingState.collectAsStateWithLifecycle()
    val lastRecording  by vm.lastRecording.collectAsStateWithLifecycle()
    val usbDevice      by vm.usbDevice.collectAsStateWithLifecycle()
    val cameraFacing    by vm.cameraFacing.collectAsStateWithLifecycle()

    val context        = LocalContext.current
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)

    var showSourceSheet by remember { mutableStateOf(false) }
    var showModelSheet  by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
        else vm.reportError("Camera permission denied — grant it in Settings to use the device camera")
    }

    LaunchedEffect(lifecycleOwner) {
        val facing = cameraFacing
        if (facing != null && sessionState == SessionState.IDLE) {
            vm.useCameraSource(facing, context, lifecycleOwner)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) vm.useFileSource(uri, context)
    }

    DisposableEffect(Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val permIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                } ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (usbManager.hasPermission(device)) vm.useUsbSource(device, ctx)
                        else usbManager.requestPermission(device, permIntent)
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) vm.useUsbSource(device, ctx)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        vm.reportError("USB camera disconnected")
                        vm.clearUsbSource()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Derived labels
    val activeSourceChoice: SourceChoice? = when {
        usbDevice    != null -> SourceChoice.USB
        cameraFacing != null -> SourceChoice.CAMERA
        videoUri     != null -> SourceChoice.FILE
        else                 -> null
    }
    val sourceLabel = when {
        usbDevice    != null -> "USB"
        cameraFacing != null -> "Camera"
        videoUri     != null -> "File"
        else                 -> "No Source"
    }
    val modelLabel = when (detectorMode) {
        DetectorMode.FAKE   -> "Fake"
        DetectorMode.TFLITE -> "TFLite"
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────
        when {
            videoUri != null -> VideoPlayer(
                uri       = videoUri!!,
                isPlaying = sessionState == SessionState.RUNNING,
                modifier  = Modifier.fillMaxSize(),
            )
            cameraFacing != null -> CameraFrameDisplay(
                frames   = vm.latestFrame,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(modifier = Modifier.fillMaxSize().background(FieldBackground))
        }

        // ── Detection overlay ─────────────────────────────────────────────────
        DetectionOverlay(detections = detections, modifier = Modifier.fillMaxSize())

        // ── HUD top-left: status + source/model when running ──────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text          = "STATUS",
                color         = FieldTextMuted.copy(alpha = 0.8f),
                fontSize      = 9.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text       = sessionState.name,
                color      = when (sessionState) {
                    SessionState.RUNNING  -> FieldAccent.copy(alpha = 0.85f)
                    SessionState.STOPPING -> Color(0xCCFFAB00)
                    SessionState.IDLE     -> FieldTextMuted.copy(alpha = 0.8f)
                },
                fontWeight = FontWeight.Bold,
                fontSize   = 12.sp,
            )
            if (sessionState == SessionState.RUNNING) {
                Text(
                    text     = "$sourceLabel · $modelLabel",
                    color    = FieldTextMuted.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── HUD top-right: FPS ────────────────────────────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text          = "FPS",
                color         = FieldTextMuted.copy(alpha = 0.8f),
                fontSize      = 9.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text  = "PRV ${"%.1f".format(previewFps)}   INF ${"%.1f".format(inferenceFps)}",
                color = FieldTextSecondary.copy(alpha = if (sessionState == SessionState.RUNNING) 0.85f else 0.5f),
                fontSize = 11.sp,
            )
        }

        // ── Error snackbar ────────────────────────────────────────────────────
        if (error != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer,
            ) { Text(error!!) }
        }

        // ── Recording saved snackbar ──────────────────────────────────────────
        if (lastRecording != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearLastRecording() }) { Text("Dismiss") }
                },
                containerColor = FieldSurface,
                contentColor   = FieldTextSecondary,
            ) { Text("Saved to Movies/DroneEdge/") }
        }

        // ── Bottom bar ────────────────────────────────────────────────────────
        BottomBar(
            modifier       = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            sessionState   = sessionState,
            recordingState = recordingState,
            sourceLabel    = sourceLabel,
            modelLabel     = modelLabel,
            onSourceClick  = { showSourceSheet = true },
            onModelClick   = { showModelSheet  = true },
            onGallery      = onGallery,
            onStart        = { vm.start() },
            onStop         = { vm.stop() },
            onArmRecording = { vm.armRecording() },
        )

        // ── Sheets ────────────────────────────────────────────────────────────
        if (showSourceSheet) {
            SourceSheet(
                activeChoice = activeSourceChoice,
                onDismiss    = { showSourceSheet = false },
                onSelect     = { choice ->
                    showSourceSheet = false
                    when (choice) {
                        SourceChoice.CAMERA -> {
                            val permission = Manifest.permission.CAMERA
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
                            } else {
                                cameraPermissionLauncher.launch(permission)
                            }
                        }
                        SourceChoice.USB -> {
                            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                            val uvcDevice  = usbManager.deviceList.values.firstOrNull { dev ->
                                (0 until dev.interfaceCount).any { i ->
                                    val iface = dev.getInterface(i)
                                    iface.interfaceClass == 0x0E && iface.interfaceSubclass == 0x02
                                }
                            }
                            if (uvcDevice == null) {
                                vm.reportError("No UVC camera found — connect a USB camera and try again")
                            } else if (usbManager.hasPermission(uvcDevice)) {
                                vm.useUsbSource(uvcDevice, context)
                            } else {
                                usbManager.requestPermission(
                                    uvcDevice,
                                    PendingIntent.getBroadcast(
                                        context, 0,
                                        Intent(ACTION_USB_PERMISSION),
                                        PendingIntent.FLAG_IMMUTABLE,
                                    ),
                                )
                            }
                        }
                        SourceChoice.FILE -> filePicker.launch("video/*")
                        SourceChoice.FAKE -> vm.useFakeSource()
                    }
                },
            )
        }

        if (showModelSheet) {
            ModelSheet(
                currentMode       = detectorMode,
                isTfliteAvailable = runCatching {
                    context.assets.open("detect.tflite").close(); true
                }.getOrDefault(false),
                onDismiss = { showModelSheet = false },
                onSelect  = { mode ->
                    showModelSheet = false
                    vm.setDetectorMode(mode, context)
                },
            )
        }
    }
}

// ── Bottom bar ────────────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    modifier:       Modifier,
    sessionState:   SessionState,
    recordingState: RecordingState,
    sourceLabel:    String,
    modelLabel:     String,
    onSourceClick:  () -> Unit,
    onModelClick:   () -> Unit,
    onGallery:      () -> Unit,
    onStart:        () -> Unit,
    onStop:         () -> Unit,
    onArmRecording: () -> Unit,
) {
    Row(
        modifier              = modifier
            .background(Color(0xEE111111))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sessionState == SessionState.IDLE) {
            OutlinedButton(
                onClick = onSourceClick,
                colors  = OutlinedButtonDefaults.outlinedButtonColors(
                    contentColor = FieldAccent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldAccent),
            ) { Text("$sourceLabel ▾", fontSize = 12.sp) }

            OutlinedButton(
                onClick = onModelClick,
                colors  = OutlinedButtonDefaults.outlinedButtonColors(
                    contentColor = FieldTextSecondary,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
            ) { Text("$modelLabel ▾", fontSize = 12.sp) }

            OutlinedButton(
                onClick = onGallery,
                colors  = OutlinedButtonDefaults.outlinedButtonColors(
                    contentColor = FieldTextMuted,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
            ) { Text("Gallery", fontSize = 12.sp) }
        }

        if (sessionState == SessionState.RUNNING) {
            RecButton(recordingState = recordingState, onArmRecording = onArmRecording)
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick  = { if (sessionState == SessionState.IDLE) onStart() else onStop() },
            enabled  = sessionState != SessionState.STOPPING,
            colors   = ButtonDefaults.buttonColors(
                containerColor = FieldAccent,
                contentColor   = Color.Black,
            ),
        ) {
            Text(
                text       = when (sessionState) {
                    SessionState.IDLE     -> "START"
                    SessionState.RUNNING  -> "STOP"
                    SessionState.STOPPING -> "STOPPING"
                },
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontSize      = 12.sp,
            )
        }
    }
}

// ── Breathing REC button ──────────────────────────────────────────────────────

@Composable
private fun RecButton(recordingState: RecordingState, onArmRecording: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recDotAlpha",
    )

    when (recordingState) {
        RecordingState.IDLE -> OutlinedButton(
            onClick = onArmRecording,
            colors  = OutlinedButtonDefaults.outlinedButtonColors(contentColor = FieldRecRedLight),
            border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
        ) { Text("REC", fontSize = 12.sp) }

        RecordingState.ARMED -> OutlinedButton(
            onClick = {},
            colors  = OutlinedButtonDefaults.outlinedButtonColors(
                containerColor = Color(0x331A0000),
                contentColor   = FieldRecRedLight,
            ),
            border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
        ) {
            Text(
                text     = "● REC",
                fontSize = 12.sp,
                color    = FieldRecRedLight.copy(alpha = dotAlpha),
                fontWeight = FontWeight.SemiBold,
            )
        }

        RecordingState.FINALIZING -> OutlinedButton(
            onClick  = {},
            enabled  = false,
            colors   = OutlinedButtonDefaults.outlinedButtonColors(contentColor = FieldTextMuted),
            border   = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
        ) { Text("Saving…", fontSize = 12.sp) }
    }
}

// ── Video player ──────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayer(uri: Uri, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode    = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }
    LaunchedEffect(isPlaying) { exoPlayer.playWhenReady = isPlaying }
    DisposableEffect(uri) { onDispose { exoPlayer.release() } }
    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            PlayerView(ctx).apply {
                player        = exoPlayer
                useController = false
                resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { it.player = exoPlayer },
    )
}

// ── Detection overlay ─────────────────────────────────────────────────────────

private val boxColor     = FieldAccent
private val labelBgColor = Color(0xCC000000)
private val labelStyle   = TextStyle(fontSize = 11.sp, color = Color.White)

@Composable
private fun DetectionOverlay(detections: List<Detection>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val cw = size.width
        val ch = size.height
        detections.forEach { det ->
            val box = det.boundingBox
            val l = box.left   * cw
            val t = box.top    * ch
            val r = box.right  * cw
            val b = box.bottom * ch
            drawRect(color = boxColor, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(3f))
            val label    = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val measured = textMeasurer.measure(label, style = labelStyle)
            val stripH   = measured.size.height.toFloat()
            drawRect(
                color   = labelBgColor,
                topLeft = Offset(l, t - stripH),
                size    = Size(maxOf(r - l, measured.size.width.toFloat()), stripH),
            )
            drawText(textLayoutResult = measured, topLeft = Offset(l + 4f, t - stripH))
        }
    }
}

// ── Camera frame display ──────────────────────────────────────────────────────

@Composable
private fun CameraFrameDisplay(
    frames:   kotlinx.coroutines.flow.StateFlow<VideoFrame?>,
    modifier: Modifier = Modifier,
) {
    val frame by frames.collectAsStateWithLifecycle()
    val bmp   = frame?.bitmap
    if (bmp != null && !bmp.isRecycled) {
        Canvas(modifier = modifier) {
            val scale = maxOf(size.width / bmp.width, size.height / bmp.height)
            val srcW  = (size.width  / scale).toInt().coerceAtMost(bmp.width)
            val srcH  = (size.height / scale).toInt().coerceAtMost(bmp.height)
            val srcX  = (bmp.width  - srcW) / 2
            val srcY  = (bmp.height - srcH) / 2
            drawImage(
                image     = bmp.asImageBitmap(),
                srcOffset = IntOffset(srcX, srcY),
                srcSize   = IntSize(srcW, srcH),
                dstOffset = IntOffset.Zero,
                dstSize   = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    } else {
        Box(modifier = modifier.background(FieldBackground))
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If there are import errors, check that all theme tokens are imported from `com.yotam.droneedge.ui.theme.*`.

- [ ] **Step 3: Run unit tests to verify nothing regressed**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: restructure LiveScreen — state-switched bar, semi-transparent HUD, breathing REC, Field Orange"
```

---

## Task 7: Gallery restyle

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt`

- [ ] **Step 1: Apply Field Orange styling and rename Recordings → Gallery**

Replace all hardcoded colour literals and rename user-facing strings. The changes are:
- `"Recordings"` title text → `"GALLERY"` (uppercase, letter-spaced)
- Back arrow text: `"←"` → styled orange
- `Color(0xFF0D1117)` → `FieldBackground`
- `Color(0xFF161B22)` → `FieldSurface`
- `Color(0xFF21262D)` → `FieldBorder`
- `Color(0xFFE6EDF3)` → `FieldTextPrimary`
- `Color(0xFF8B949E)` → `FieldTextSecondary`
- `Color(0xFF757575)` → `FieldTextMuted`
- `Color.White` → `FieldTextPrimary`
- Header back arrow: add `color = FieldAccent`

Full replacement of `RecordingsScreen.kt`:

```kotlin
package com.yotam.droneedge.ui.recordings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurface
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val uri:         Uri,
    val sessionName: String,
    val durationMs:  Long,
    val dateMs:      Long,
)

@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recordings by produceState<List<RecordingEntry>>(emptyList()) {
        value = withContext(Dispatchers.IO) { queryRecordings(context) }
    }
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    if (playingEntry != null) {
        RecordingPlayer(entry = playingEntry!!, onBack = { playingEntry = null })
    } else {
        RecordingList(recordings = recordings, onSelect = { playingEntry = it }, onBack = onBack)
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun RecordingList(
    recordings: List<RecordingEntry>,
    onSelect:   (RecordingEntry) -> Unit,
    onBack:     () -> Unit,
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

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings found.", color = FieldTextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings) { entry ->
                    RecordingRow(entry = entry, onClick = { onSelect(entry) })
                    HorizontalDivider(color = FieldBorder)
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(entry: RecordingEntry, onClick: () -> Unit) {
    val dateStr = remember(entry.dateMs) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(entry.dateMs))
    }
    val durationStr = remember(entry.durationMs) {
        val s = entry.durationMs / 1000
        "%d:%02d".format(s / 60, s % 60)
    }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = entry.sessionName, color = FieldTextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(text = dateStr, color = FieldTextSecondary, fontSize = 11.sp)
        }
        Text(text = durationStr, color = FieldTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Full-screen player ────────────────────────────────────────────────────────

@Composable
private fun RecordingPlayer(entry: RecordingEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember(entry.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(entry.uri))
            repeatMode    = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(entry.uri) { onDispose { exoPlayer.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    player        = exoPlayer
                    resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = true
                }
            },
            update = { it.player = exoPlayer },
        )
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x80000000)),
        ) {
            Text("←", color = FieldAccent, fontSize = 20.sp)
        }
        Text(
            text     = entry.sessionName,
            color    = FieldTextSecondary.copy(alpha = 0.8f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color(0x80000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── MediaStore query ──────────────────────────────────────────────────────────

private fun queryRecordings(context: Context): List<RecordingEntry> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryRecordingsMediaStore(context)
    else queryRecordingsFileSystem()

private fun queryRecordingsMediaStore(context: Context): List<RecordingEntry> {
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
            results += RecordingEntry(
                uri         = ContentUris.withAppendedId(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    cursor.getLong(idCol),
                ),
                sessionName = sessionName,
                durationMs  = cursor.getLong(durCol),
                dateMs      = cursor.getLong(dateCol) * 1000L,
            )
        }
    }
    return results
}

private fun queryRecordingsFileSystem(): List<RecordingEntry> {
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
                uri         = Uri.fromFile(mp4),
                sessionName = dir.name,
                durationMs  = 0L,
                dateMs      = dir.lastModified(),
            )
        }
        ?: emptyList()
}
```

- [ ] **Step 2: Build + test**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/recordings/RecordingsScreen.kt
git commit -m "feat: rename Recordings → Gallery, apply Field Orange styling"
```

---

## Task 8: Final build gate and pull request

- [ ] **Step 1: Full build + tests**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Open pull request**

```bash
gh pr create \
  --title "feat: UI/UX redesign — Field Orange theme, restructured controls, model selection" \
  --body "$(cat <<'EOF'
## Summary
- Field Orange visual theme (near-black background, #F97316 accent) applied throughout
- Model selection screen shown on every app launch; last choice persisted via SharedPreferences
- Live Screen bottom bar state-switched: IDLE shows Source/Model/Gallery/START; RUNNING collapses to REC/STOP
- Source and Model selection moved into ModalBottomSheet panels (scalable as sources grow)
- HUD overlays semi-transparent so they don't block the field of view
- REC dot has a breathing animation while recording is active
- Bounding box colour updated from cyan to Field Orange to match theme
- "Recordings" renamed to "Gallery" throughout

## Test plan
- [ ] Launch app — model selection screen appears, TFLite option disabled if asset missing
- [ ] Select a model, close and reopen app — previous model pre-selected
- [ ] Tap Source button on Live Screen — sheet opens with all sources listed
- [ ] Tap Model button — sheet opens, active model shows checkmark
- [ ] Start a session — bottom bar collapses to REC + STOP; HUD shows source and model name
- [ ] REC button dot breathes while recording is armed
- [ ] Tap Gallery — opens gallery screen with orange back arrow and "GALLERY" header
- [ ] Run `./gradlew test` — all unit tests pass

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Report result**

Paste the PR URL here so it can be reviewed before merging.
