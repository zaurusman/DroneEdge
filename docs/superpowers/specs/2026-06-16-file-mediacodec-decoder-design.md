# File MediaCodec Decoder — Design

**Date:** 2026-06-16
**Status:** Draft for review
**Branch (planned):** `feature/file-mediacodec-source`

## Problem

On a video-file source, detection runs at ~3–6 fps and recorded MP4s look choppy.
Root cause is `FileReplayVideoSource`: it decodes every frame with
`MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` in a no-delay loop
(~150 ms/frame ≈ 6 fps). Both inference (starved) and recording (one encoded
frame per source frame) inherit that cap. The on-screen preview *looks* smooth
only because display is a separate ExoPlayer instance; the boxes + recording use
the slow MMR frames.

## Goal

Decode the file fast enough that inference runs at its true throughput (~14 fps,
the DJI-stream mark) and recordings are smooth and full-resolution — by moving
the file source to the same MediaCodec → Surface + PixelCopy architecture the DJI
stream already uses.

## Non-goals

- Changing the DJI source (the same technique could later raise DJI recording
  quality, but that is out of scope here).
- Perfect A/V frame-exact sync between overlay and pixels (surface render gives
  us "exact enough"; we do not build a frame-stepping scheduler).
- Audio.

## Chosen approach (Approach B)

Single hardware decoder per file:

```
MP4 file ──MediaExtractor──► MediaCodec(decoder) ──renders──► display Surface (full-res, GPU)
                                                       │
                                              PixelCopy readback
                                                       │
                                           VideoFrame.bitmap ──► inference + recording
```

Why B over "keep ExoPlayer + add a frame decoder":
- **One** decoder instead of two running on the same file.
- No per-frame YUV→ARGB conversion in Kotlin — PixelCopy returns ARGB directly
  (GPU readback).
- **Rotation is automatic**: decoding to a Surface applies the file's rotation
  metadata, and PixelCopy grabs the already-correct pixels. (Raw ByteBuffer
  decode would have required manual `KEY_ROTATION` handling — the documented MMR
  gotcha.)
- Boxes are exactly aligned to what's displayed, and it matches the eventual
  drone architecture.

## Components

### New: `FileMediaCodecVideoSource : VideoSource` (`video/`)

Mirrors `DjiGogglesAccessorySource`'s decode/PixelCopy/cancellation structure,
with file-specific input + pacing. Constructed with the file `Uri` and the
display `Surface` (like the DJI source's `renderSurface`).

Responsibilities:
1. `MediaExtractor` selects the video track; reads its format (width, height,
   rotation, duration).
2. `MediaCodec` decoder configured **to the display Surface** (`configure(fmt,
   surface, null, 0)`), so display is full-resolution and auto-rotated.
3. **Feed loop:** read samples → `queueInputBuffer` with `extractor.sampleTime`
   → `advance()`. Drain output → `releaseOutputBuffer(idx, renderTimestampNs)`
   so the surface renders at 1× real time.
4. **Loop:** when `readSampleData` returns < 0, `extractor.seekTo(0,
   SEEK_TO_CLOSEST_SYNC)` and continue — seamless replay, no EOS/flush dance.
5. **PixelCopy loop** (separate coroutine, mirrors DJI): captures the rendered
   surface into a **full-resolution** ARGB bitmap, reusing one capture bitmap and
   `getAndSet(...).recycle()` to bound allocation. Each emitted `VideoFrame`
   carries the latest capture as `bitmap`, with `timestampMs` = wall clock (so
   the recorder's PTS are real-time, matching display).
6. `width`/`height` report the **rotated** (display) dimensions, so the recorder
   encodes at the correct orientation/size.

Reports rotated dimensions: for rotation 90/270, swap width/height.

### Changed: `LiveViewModel`

- File-picker path constructs `FileMediaCodecVideoSource` instead of
  `FileReplayVideoSource`. As with DJI, the source is (re)created at `start()`
  once the display Surface is available; reuse/generalize the existing
  `_djiSurface` mechanism into a shared "render surface for surface-based
  sources."
- **Inference & 14 fps:** `TfliteDetector.fillInputBuffer` *already* scales its
  input bitmap (bilinear `Canvas.drawBitmap`) to the model's tensor size. So the
  inference path needs **no change** — feed it the full-res `frame.bitmap`
  directly. (Pre-downscaling in the ViewModel would be counterproductive: it
  reads all 1080p pixels to shrink, then the detector scales again — strictly
  more work than letting the detector scale once.) The only added cost vs the
  DJI path is that the detector's internal scale reads a larger source bitmap
  (~a few ms of `preMs`, which `TfliteDetector` already logs to
  `tflite_timing.txt`).
  - **Verify on device.** If measured inference fps drops below the ~14 target,
    apply the documented fallback (Task: dedicated small capture) — a second
    PixelCopy into a 640×360 bitmap (GPU-scaled during readback, never a full-res
    CPU read), carried on a new `VideoFrame.inferenceBitmap` field that the
    inference loop prefers over `bitmap`. Start without it (YAGNI).
- Recording is unchanged: `recorder.onFrame` receives the **full-res** bitmap →
  sharp annotated recordings. `recorder.start(videoSource.width, height, …)`
  already uses the (now rotated) source dimensions.

### Changed: `LiveScreen`

- For file sources, render a `SurfaceView` (as the DJI path does) and hand its
  `Surface` to the ViewModel, instead of the ExoPlayer `PlayerView`. Remove
  ExoPlayer **only** from the live file preview.
- ExoPlayer stays in `RecordingsScreen` (saved-file playback) — untouched.

### Removed (after validation)

- `FileReplayVideoSource` once the new source is verified on device.

## Data flow & the two resolutions

- **Display:** full-res, GPU surface render. Always maxed; capture size does not
  affect it.
- **Recording:** full-res PixelCopy bitmap → recorder → sharp 1080p (or native)
  recordings.
- **Inference:** receives the same full-res `frame.bitmap`; `TfliteDetector`
  scales it internally (as it already does). Verify fps on device; fall back to
  a dedicated 640×360 capture only if needed.

### Allocation note

Full-res ARGB capture at source fps is ~8 MB/frame. We bound churn by reusing a
single capture bitmap and recycling the previous emitted copy
(`getAndSet(...).recycle()`), the proven DJI pattern. If steady-state GC pressure
proves noticeable when *not* recording, a documented follow-up is to capture at a
reduced size while idle and full-res only while armed. Starting simple: full-res
always.

## Pacing & looping

- **Display pacing:** `releaseOutputBuffer(idx, renderTimestampNs)` with
  `renderTimestampNs` derived from the sample PTS anchored to a wall-clock start,
  giving smooth 1× playback. (Replaces MMR's wall-clock-seek trick.)
- **Loop:** `extractor.seekTo(0, SEEK_TO_CLOSEST_SYNC)` on sample exhaustion;
  reset the wall-clock anchor so playback continues at 1×.

## Error handling & cancellation

- Codec/extractor init failure → throw; `LiveViewModel`'s collect catch surfaces
  it (now a real message, not the old null-message fallback). Log to the existing
  file logger (`getExternalFilesDir("logs")`).
- Cancellation: `running` flag + `isActive`; `finally` releases codec, extractor,
  and recycles bitmaps — mirrors the DJI source.

## Testing

- **Unit-testable pure helpers** (extract these so they don't need the
  framework):
  - rotation → reported `(width, height)` mapping (0/90/180/270).
  - PTS → render-timestamp pacing math (sample time + anchor → render ns; loop
    reset).
- **On-device** (emulator + Tab S10+): file plays full-res and smooth; HUD
  preview/inference fps rises from ~5 toward ~14; recorded MP4 is smooth,
  correctly oriented, full-resolution, correct colours; loop works; start/stop
  and source-switch are clean (no leaked codec).

## Risks

- **SurfaceView lifecycle:** surface availability vs `start()` timing — mitigated
  by reusing the DJI surface-at-start pattern.
- **PixelCopy on emulator:** validated working for DJI; re-confirm for files.
- **Decoder variance across devices** (already true for DJI MediaCodec path).

## Files

- New: `app/src/main/java/com/yotam/droneedge/video/FileMediaCodecVideoSource.kt`
- Changed: `LiveViewModel.kt` (source construction, render-surface wiring,
  inference downscale), `LiveScreen.kt` (file SurfaceView display)
- Removed (after validation): `FileReplayVideoSource.kt`
- Optional refactor: extract a shared `SurfaceFrameCapturer` (PixelCopy loop)
  reused by both DJI and file sources.

## Out-of-scope follow-ups

- Apply the same full-res capture to **DJI recording** (currently 640×360
  upscaled to 1080p — soft).
- Idle-time reduced-resolution capture optimization.
