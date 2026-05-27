# DroneEdge Roadmap

## Phase 1 - App Skeleton

Goal: Prove the live processing architecture without real drone input.

- Jetpack Compose app shell
- Live screen
- Fake video source
- Fake detector
- Detection overlay
- Start / stop controls
- Session state
- Preview FPS and inference FPS

Success criteria:

- App builds
- App runs on emulator
- Fake detections move on screen
- Architecture has VideoSource and Detector interfaces

## Phase 2 - Local Video Replay

Goal: Develop using real video files before hardware is available.

- Pick local MP4 file
- Decode / play video in app
- Feed frames to detector pipeline
- Draw fake detections over real video

Success criteria:

- Local drone video can be replayed
- Detection overlay stays aligned with video

## Phase 3 - TensorFlow Lite

Goal: Run a real object detection model.

- Add TFLite dependency
- Load model from assets
- Load labels
- Add TfliteDetector
- Add model output parser interface
- Add fake/real detector switch

Success criteria:

- Model runs off UI thread
- Bounding boxes appear from real inference
- Inference FPS is visible

## Phase 4 - Recording

Goal: Save useful session outputs.

- Create session folders
- Save metadata JSON
- Save detection events
- Save screenshots on detection
- Add basic gallery

Success criteria:

- Each run creates a session folder
- Outputs can be reviewed after the run

## Phase 5 - Video Input Hardware

Goal: Add real external inputs.

- Camera source
- USB/UVC source
- Test with external capture device
- Measure latency

Success criteria:

- App can process live external video input

## Phase 6 - DJI Goggles Integration

Goal: Receive live stream from DJI Goggles 2 / Integra.

- Research compatible open-source repos
- Analyze USB/Wi-Fi protocol options
- Implement DjiGogglesVideoSource
- Keep fallback path through UVC/capture

Success criteria:

- App receives DJI live view
- Inference and recording work on live drone stream