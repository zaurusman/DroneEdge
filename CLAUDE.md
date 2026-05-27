# DroneEdge - Claude Code Project Instructions

## Project Summary

DroneEdge is a native Android tablet app for live drone-video computer vision.

The app should eventually receive a live video stream from DJI Avata / Avata 2 goggles, run a TensorFlow Lite object detection model on the frames, draw bounding boxes live, and automatically record annotated video sessions to local device storage.

For early development, do not implement DJI ingestion yet. Development starts on a Mac using emulator-friendly video sources.

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

Implement the app in this order:

1. Fake video source with fake detections
2. Local MP4 replay source
3. Real TensorFlow Lite detector
4. Session recording and metadata
5. USB/UVC source
6. DJI goggles source

Do not skip directly to DJI integration.

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

## Claude Workflow Rules

Before making large changes:

1. Briefly explain the plan.
2. Make the smallest useful implementation step.
3. Run the relevant Gradle build/check command.
4. Fix compile errors before stopping.
5. Summarize what changed and what remains.

When uncertain, prefer creating interfaces and placeholders instead of guessing irreversible implementation details.