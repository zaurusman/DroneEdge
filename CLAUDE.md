# DroneEdge - Claude Code Project Instructions

## Project Summary

DroneEdge is a native Android tablet app for live drone-video computer vision.

The app should eventually receive a live video stream from DJI Avata / Avata 2 goggles, run a TensorFlow Lite object detection model on the frames, draw bounding boxes live, and automatically record annotated video sessions to local device storage.

For early development, do not implement DJI ingestion yet. Development starts on a Mac using emulator-friendly video sources.

## Build & Deploy

- Build only: `./gradlew assembleDebug`
- Build + install to connected device/emulator: `./gradlew installDebug`
- Run unit tests: `./gradlew test`

## Current Hardware Target

- Development machine: macOS
- Initial runtime target: Android emulator and later Android tablet
- Intended field device: Android tablet, likely Samsung Galaxy Tab S10+
- Drone targets: DJI Avata and DJI Avata 2
- Goggles targets: DJI Goggles 2 and DJI Goggles Integra

## Core Architecture Rule

Video input must always be abstracted behind a replaceable source interface.

Expected video sources over time:

- FakeVideoSource
- FileReplayVideoSource
- CameraVideoSource
- UsbUvcVideoSource
- DjiGogglesVideoSource

Do not hardcode the app around DJI ingestion, USB, or a specific camera API.

## Build Order

Phases completed: 1–4. Remaining:

5. USB/UVC source
6. DJI goggles source

Do not skip directly to DJI integration.

## Package Map

- `video/`          — VideoSource interface + FakeVideoSource, FileReplayVideoSource
- `detection/`      — Detector interface + FakeDetector, TfliteDetector, SsdOutputParser
- `recording/`      — SessionRecorder interface + VideoSessionRecorder, YuvConversion
- `ui/live/`        — LiveScreen, LiveViewModel, session/recording state enums
- `ui/recordings/`  — RecordingsScreen (MediaStore query + ExoPlayer playback)
- `ui/theme/`       — DroneEdgeTheme

## Technology Choices

- Kotlin
- Jetpack Compose
- MVVM
- Coroutines and Flow
- TensorFlow Lite / LiteRT for inference
- MediaCodec / MediaMuxer later for recording
- Prefer simple Android-native APIs before adding heavy dependencies

## Coding Standards

- Keep code clean, modular, and easy to replace.
- Prefer small interfaces and isolated implementations.
- Avoid overengineering.
- Do not introduce cloud services.
- Do not introduce backend code.
- Do not introduce React Native, Flutter, or web technologies.
- Avoid DJI-specific code until the app pipeline is stable.
- Keep Gradle dependencies minimal and explain any new dependency before adding it.

## Runtime Principles

The app should be designed for real-time use:

- Do not block the UI thread.
- Run detection off the main thread.
- Track preview FPS and inference FPS.
- Allow inference to run at a lower FPS than video preview.
- Use the latest detection result for overlay if inference is slower than video.
- Prefer graceful degradation over crashes.

## Recording Principles

The long-term goal is to save:

- Annotated MP4 video
- Detection metadata JSON
- Optional detection screenshots
- Session folder per flight/run

Initial implementations may use simpler placeholders, but recording code should be isolated so it can later be replaced with MediaCodec / MediaMuxer.

## Important Constraints

- The app is a passive observer, detector, and recorder.
- It should not control the drone.
- It should not be treated as the pilot’s primary flight display.
- Latency and thermal behavior matter.
- DJI stream access is uncertain and should be isolated behind DjiGogglesVideoSource.

## GitHub

Remote: https://github.com/zaurusman/DroneEdge  
Owner: zaurusman  
Default branch: main

## Git / GitHub Workflow

Every feature must follow this cycle — no exceptions:

1. **Branch** — create `feature/<short-name>` (new work) or `fix/<short-name>` (bug fix) from the latest `main` before touching code.
2. **Implement** — make the smallest useful step; keep commits atomic and well-described.
3. **Test** — write unit or integration tests for the new behaviour; run them with Gradle before merging.
4. **Build gate** — run `./gradlew assembleDebug` (and `./gradlew test` if unit tests exist); fix all errors.
5. **Merge** — only merge back to `main` when the build is green and tests pass; use a PR (`gh pr create`) so there is a record.
6. **Summarize** — after merging, report branch name, what changed, build result, and commit hash.

Never commit directly to `main`.  
Never merge a branch that has failing tests or compile errors.

## Claude Workflow Rules

Before making large changes:

1. Briefly explain the plan.
2. Create a `feature/<name>` branch if not already on one.
3. Make the smallest useful implementation step.
4. Write tests for the new behaviour.
5. Run the relevant Gradle build/check command.
6. Fix compile errors before stopping.
7. Summarize what changed and what remains.

When uncertain, prefer creating interfaces and placeholders instead of guessing irreversible implementation details.

## Android Gotchas

- `MediaStore.Files` rejects `Movies/` as `RELATIVE_PATH` on API 29+; write non-video files (JSON, etc.) to `context.getExternalFilesDir()` instead.
- `MediaMetadataRetriever.getFrameAtTime()` on API 27+ already applies the video's rotation metadata — do not apply an additional rotation or frames will be double-rotated.
- MediaCodec `getInputBuffer()` with `COLOR_FormatYUV420Flexible` expects **I420 planar** (Y plane, then full U plane, then full V plane), not NV12 (interleaved UV). Wrong format causes green/magenta colour corruption.
- `FileReplayVideoSource` uses `MediaMetadataRetriever` (≈6 fps). Use wall-clock elapsed time for `videoTimeMs` so source content advances at 1× speed; a fixed `intervalMs` increment causes slow-motion recordings.