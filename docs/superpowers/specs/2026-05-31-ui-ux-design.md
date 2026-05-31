# DroneEdge UI/UX Design Spec

**Date:** 2026-05-31  
**Scope:** Visual redesign + structural layout overhaul for LiveScreen, model selection startup, and Gallery screen.

---

## Design Principles

- **Field ops first** — large tap targets, high contrast, glove-friendly.
- **No emojis** anywhere in the UI.
- **Minimal chrome while flying** — controls collapse when session is running.
- **Explicit state** — user always knows which source and model are active.

---

## Visual Theme: Field Orange

| Token | Value | Usage |
|-------|-------|-------|
| Background | `#0A0A0A` | Screen background |
| Surface | `#111111` | Bottom bar, header bar |
| Surface elevated | `#161616` | Sheets, cards |
| Border | `#1F2937` | Dividers, inactive borders |
| Accent | `#F97316` | Primary action buttons, active selections, orange accent |
| Text primary | `#E5E7EB` | Titles, session names |
| Text secondary | `#9CA3AF` | Labels, metadata |
| Text muted | `#6B7280` | Inactive items, placeholders |
| Error / REC | `#DC2626` / `#F87171` | Recording indicator, error states |
| HUD opacity | ~40% (`#00000066` background) | Semi-transparent overlays on video |

Bounding boxes drawn in `#F97316` (orange) instead of the previous cyan, to match the theme.

---

## Screen 1: Model Selection (every app launch)

Appears as a full-screen modal before the Live Screen on every cold start.

**Layout:**
- App name "DRONEEDGE" centered, orange, large weight.
- Subtitle "SELECT DETECTION MODEL" in muted uppercase.
- Vertical list of model options as tappable cards.
- Selected card has orange border + orange radio indicator.
- "CONFIRM" button below the list, full-width, orange fill, disabled until a selection is made.

**Model options:**
- **Fake Detector** — generates random bounding boxes; always available.
- **TFLite — SSD MobileNet** — on-device inference; disabled (greyed, labelled "Model not found") if the `.tflite` asset is absent.
- Future models appear here as additional cards when bundled.

**Behaviour:**
- Pre-selects the last used model (persisted via `SharedPreferences`).
- No dismiss / back — user must confirm a selection to proceed.
- After confirmation, navigates to the Live Screen.

---

## Screen 2: Live Screen

### HUD (always visible over video)

**Top-left block** (`#00000066` background, `backdrop-filter: blur`):
- Label: "STATUS" (muted, 9sp, letter-spaced)
- Session state: "IDLE" (muted) / "RUNNING" (orange, bold) / "STOPPING" (amber)
- When RUNNING: second line shows `<source> · <model>` (e.g. "CAM: back · TFLite")

**Top-right block** (`#00000066` background):
- Label: "FPS" (muted)
- Single line: `PRV 29.8   INF 12.3` — muted when idle, slightly brighter when running

Both blocks are semi-transparent so they sit over the video feed without blocking it.

### Bottom bar — IDLE state

Single row, `#111111EE` background, 1px top border `#1F2937`:

```
[ Source ▾ ]  [ Model ▾ ]  [ Gallery ]  ············  [ START ]
```

- **Source button** — orange border + text when a source is active; neutral when no source selected. Label shows active source name (e.g. "Camera ▾", "USB ▾", "No Source ▾").
- **Model button** — neutral border; label shows active model name (e.g. "TFLite ▾", "Fake ▾").
- **Gallery** — neutral, navigates to Gallery screen.
- **START** — orange fill, black text, right-aligned.

### Bottom bar — RUNNING state

Collapses to two items only:

```
[ ● REC ]  ·····················  [ STOP ]
```

- **REC button** — red border, dark red background, red text. The `●` dot has a CSS breathing animation (pulse in/out, ~1.4s cycle) while `RecordingState == ARMED`.
- When `RecordingState == FINALIZING`, button shows "Saving…" and is disabled.
- **STOP** — orange fill, black text.

### Source Sheet

Opens as a bottom sheet from the Source button (IDLE only).

Options (in order):
1. Camera (back) — tappable
2. USB / UVC — tappable; shows error snackbar if no UVC device found
3. Video File — opens system file picker; if user cancels, source remains unchanged
4. DJI Goggles — greyed out, "coming soon" label, not tappable
5. Fake (dev) — always available

Active source shown with orange highlight + checkmark. Selecting a new source dismisses the sheet and updates the button label.

### Model Sheet

Opens as a bottom sheet from the Model button (IDLE only). Same visual pattern as Source Sheet.

Options:
1. TFLite — SSD MobileNet (disabled if asset missing)
2. Fake Detector

Active model highlighted in orange. Selecting a model updates the button label immediately; change takes effect on next session start.

### Error / notification snackbars

Unchanged in position (bottom-center, above the bar). Styled to match Field Orange theme (dark container, orange or red content colour as appropriate).

---

## Screen 3: Gallery

### List view

- Header bar: `#111111` background, orange back arrow `←`, "GALLERY" title bold white.
- Each row: small video thumbnail placeholder (52×36dp, `#161616` fill) + session name + date/time + duration (right-aligned).
- Divider: `#1F2937`.
- Empty state: "No recordings found." centred, muted.

### Player view

- Full-screen black background.
- ExoPlayer native controls for play/pause/seek (styled by the system; no custom implementation needed).
- Back button overlay (top-left): `#00000088` pill, orange `←`.
- Session name overlay (top-right): `#00000088` pill, muted text.

---

## What Does Not Change

- Navigation structure (LiveScreen ↔ GalleryScreen) remains a simple two-screen swap, no new nav library.
- `VideoSource` interface and all source implementations are untouched.
- `Detector` interface and implementations are untouched.
- `SessionRecorder` / recording pipeline is untouched.
- No new Gradle dependencies required for this redesign.

---

## Out of Scope

- Front camera toggle (not yet needed).
- Thumbnail extraction for Gallery rows (placeholder tiles are acceptable for now).
- Dark/light theme toggle (always dark).
- Tablet-specific multi-pane layout.
