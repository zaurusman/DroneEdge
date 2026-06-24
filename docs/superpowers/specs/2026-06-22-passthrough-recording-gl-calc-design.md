# DJI Passthrough Recording + GL "Calc" Annotator — Design

**Date:** 2026-06-22
**Status:** Draft for review
**Branch:** `feat/recording-passthrough-calc`

## Problem

DJI recordings are currently soft: the recorder upscales the 640×360 PixelCopy
inference bitmap to 1080p. Trying to capture full-res from the live SurfaceView
(PixelCopy) starved the decoder's buffer queue → growing latency, slow-motion
recording, inference dropping to 10 fps. Burning boxes in live at full-res/30 fps
isn't viable without a complex real-time GL pipeline.

The reference app (Sirena) records **full-res at the stream's native fps with no
live cost** by *muxing the drone's already-encoded H.264 directly* (passthrough)
— its recordings carry no boxes; detections are a live UI overlay only.

## Goal

Two-step feature:
1. **DJI passthrough recording** — record the raw drone H.264 to MP4 (full-res,
   native fps, ~zero live cost), plus the `detections.json` we already write.
2. **Offline GL "calc"** — on demand, turn a saved (raw MP4 + JSON) recording
   into a new annotated MP4 by replaying the saved boxes, via a GL transcode
   (full-res, all frames, several× real-time, no real-time stall risk).

## Decisions (locked)

- **Scope: DJI only.** File/camera/USB recording unchanged (the file source's
  annotated re-encode already works).
- **Boxes: replay saved `detections.json`** (no re-inference). The live ~14 fps
  detections are drawn on every frame (last-known box between detections, like
  the live overlay).
- **Build order: Part 1 first** (independently useful — sharp DJI recordings
  immediately), then Part 2 (the GL-heavy piece).

## Shared contract: one timeline

The two parts meet at a single time origin. The recorder already writes
`{"sessionStart": <wallClockMs>}` as the first JSON line, and detection events
carry wall-clock `timestampMs`. Part 1 MUST stamp video sample PTS **relative to
that same `sessionStart`** (first sample at PTS 0). Then Part 2 maps any decoded
frame: `frameWallClockMs = sessionStart + framePtsMs`, and looks up the
detection event with the greatest `timestampMs ≤ frameWallClockMs`. Detection
bounding boxes are already normalized (0..1), so they scale to any resolution.

---

## Part 1 — DJI passthrough recording

### Components
- **`H264PassthroughRecorder`** (`recording/`) — implements `SessionRecorder`.
  Gets **video** from encoded NAL samples (via `encodedSink`) written to a
  `MediaMuxer`; gets **detections** from the existing `onFrame(frame, detections)`
  calls `LiveViewModel` already makes when armed — it ignores `frame.bitmap` and
  uses only `detections` to append the JSON sidecar. Reuses the existing
  JSON-sidecar + MediaStore (`IS_PENDING`) finalization logic from
  `VideoSessionRecorder` (extract the shared storage/JSON helpers so both
  recorders use them — don't duplicate). So: video ← `encodedSink`, JSON ←
  `onFrame`, both keyed to the same `sessionStart`.
- **DJI source encoded-sample hook** — `DjiGogglesAccessorySource` already
  reassembles complete NAL units (split on `00 00 01`) to feed the decoder. Add
  an optional sink it calls with each complete NAL:
  `var encodedSink: ((nal: ByteArray, ptsUs: Long, isKeyframe: Boolean) -> Unit)? = null`.
  Display + PixelCopy inference paths are untouched. When the sink is null
  (default), behaviour is exactly as today.
- **`LiveViewModel`** — when the active source is `DjiGogglesAccessorySource`
  and recording is armed, set the source's `encodedSink` to forward NALs to the
  `H264PassthroughRecorder`; clear it on disarm. The on-screen overlay and the
  detection-JSON writing are unchanged.

### Data flow (recording armed)
```
USB → NAL reassembly ─┬─► decoder (display + PixelCopy inference)   [unchanged]
                      └─► encodedSink ─► H264PassthroughRecorder ─► MediaMuxer (raw .mp4)
detections (live) ─────────────────────────────────────────────► detections.json
```

### The two hard bits
1. **SPS/PPS → muxer track.** `MediaMuxer.addTrack` needs `csd-0` (SPS) and
   `csd-1` (PPS) before `start()`. Parse NAL type = `nal[startCodeLen] & 0x1F`
   (7=SPS, 8=PPS, 5=IDR). Buffer until both SPS and PPS are seen, build the AVC
   `MediaFormat` (width/height from the SPS or the source's reported dims; set
   `csd-0`/`csd-1`), then `addTrack` + `start()`. Begin writing samples at the
   first IDR; mark IDR samples with `BUFFER_FLAG_KEY_FRAME`. Drop any
   pre-first-keyframe non-parameter NALs.
2. **Sample PTS.** Synthesize from wall-clock arrival relative to recording
   start (`ptsUs = (nowMs - sessionStartMs) * 1000`), so playback runs at 1× and
   the timeline matches the JSON. (The DJI transport gives no container PTS.)

### Error handling
- No keyframe/SPS within a timeout → recorder reports an error (don't leave a
  pending MediaStore row); finalize cleanly on stop. Mirror the existing
  `VideoSessionRecorder` stop/finalize discipline (no muxer double-start, always
  release).

### Why this is safe for the live path
A NAL tee is a buffer copy + a channel send per NAL — negligible next to USB
read + decode. No decode/encode/PixelCopy added. This is exactly Sirena's
proven approach.

---

## Part 2 — Offline GL "calc" annotator

### Pipeline (Grafika *DecodeEditEncode* pattern)
```
MediaExtractor(raw.mp4) → MediaCodec DECODER → SurfaceTexture (GL OES texture)
  → GL composite [ video-frame quad + box-overlay quad ] onto ENCODER input Surface
  → MediaCodec ENCODER → MediaMuxer(annotated.mp4)
```
Runs flat-out (no pacing). Decoder PTS are carried to the encoder so output
timing matches the source.

### Boxes overlay (pragmatic)
For each decoded frame: compute `frameWallClockMs`, ask `DetectionTrack` for the
boxes at that time, render boxes **and text labels** into a transparent ARGB
`Bitmap` via `Canvas` (reuse the recorder's `Paint`/`drawRect`/label code),
upload it to a GL texture, and blend it over the video-frame quad. Rationale:
pure-GL would force hand-rolled text rendering; `Canvas` does text for free and
the overlay is mostly transparent (cheap upload).

### Components
- **`DetectionTrack`** — parses `detections.json` into a time-ordered index;
  `boxesAt(wallClockMs): List<Detection>` returns the last-known detections at or
  before that time. **Pure / unit-testable.**
- **`OverlayRenderer`** — `(List<Detection>, width, height) -> Bitmap` (transparent
  + boxes/labels), reusing recorder draw code. Testable with a real Bitmap.
- **`VideoAnnotator`** — the GL transcode engine (MediaExtractor + decoder +
  EGL + encoder + muxer). Orchestrates the loop; on-device verified.
- **GL helpers** — minimal `EglCore`, `WindowSurface`, `TextureRenderer` (OES
  external sampler + SurfaceTexture transform matrix), ported from Grafika.
- **UI / job** — a **"Calc boxes"** action per session in `RecordingsScreen`,
  running as a background `WorkManager` job with progress (`framesDone/total`);
  writes `annotated.mp4` beside the raw and registers it in MediaStore.

### Input requirements
A session is "calc-able" if it has a raw video + a `detections.json` with a
`sessionStart`. The action is hidden/disabled otherwise.

### Error handling
- Missing/corrupt JSON or no video track → fail the job with a clear message; do
  not leave a partial MediaStore row.
- Encoder/EGL init failure → abort cleanly, release all (decoder, encoder, muxer,
  EGL, extractor).

---

## Testing

- **Unit (pure):**
  - `DetectionTrack`: last-known lookup, boundaries (before first / after last
    detection), PTS→wall-clock mapping.
  - NAL parsing: type extraction, SPS/PPS capture, first-keyframe gating.
- **On-device (emulator + Tab):**
  - Part 1: recording plays at 1× full-res, correct duration; stop is clean (no
    pending row, no hang); live latency/inference unaffected while recording.
  - Part 2: annotated output is full-res, boxes correctly placed and
    time-aligned, all frames covered; **measure calc throughput on the Tab**
    (target several× real-time).

## Risks
- Part 1: SPS/PPS/keyframe-start handling and PTS correctness (slow-motion or
  unplayable file if wrong) — covered by NAL unit tests + on-device duration
  check.
- Part 2: EGL/OES-transform correctness (upside-down/stretched frames are the
  classic bug) — verified on-device early on one clip. Offline GL has **no
  real-time stall risk** — the failure mode is a wrong-looking frame, not a
  pipeline collapse.

## Out of scope / follow-ups
- Re-inference during calc (every-frame / heavier model) — future toggle.
- Passthrough recording for non-DJI sources.
- Live burned-in GL pipeline (the offline GL helpers here de-risk it if ever
  wanted).
