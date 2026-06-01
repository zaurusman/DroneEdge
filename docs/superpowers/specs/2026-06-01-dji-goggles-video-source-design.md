# Phase 6: DJI Goggles Video Source — Design Spec

**Date:** 2026-06-01  
**Status:** Approved  
**Target hardware:** DJI Goggles 2, DJI Goggles Integra  
**Target drone:** DJI Avata, DJI Avata 2  

---

## Overview

Phase 6 adds `DjiGogglesVideoSource`, a `VideoSource` implementation that receives live H.264 video from DJI Goggles connected to an Android tablet via USB-C OTG. Video is pulled using DJI's proprietary DUML (DJI Unified Message Link) protocol, decoded with Android's native MediaCodec, and emitted as `VideoFrame` through the same cold-Flow contract used by every other source in the pipeline.

No DJI SDK is introduced. No cloud services are introduced. No new Gradle dependencies are required.

---

## Connection Topology

```
DJI Avata/Avata 2
      │  (O3 wireless link)
DJI Goggles 2 / Integra
      │  (USB-C OTG cable)
Android Tablet  ←  DroneEdge (passive observer)
```

The app is a read-only consumer. It never sends flight-control commands to the drone. The only DUML packets it transmits are the minimum handshake required to start the video stream.

---

## Architecture

### Components

| Component | Kind | Responsibility |
|---|---|---|
| `DumlFramer` | Pure Kotlin class | Parse inbound DUML packets from raw bytes; build outbound DUML packets with correct framing and CRCs. No Android dependencies. |
| `DjiGogglesVideoSource` | `VideoSource` impl | Open DJI USB device, claim streaming interface, send DUML startup sequence, assemble H.264 NAL units, decode via MediaCodec, emit `VideoFrame`. |
| `DumlFramerTest` | JUnit 4 | Unit-tests for framer logic — no hardware required. |
| `LiveViewModel` (modified) | ViewModel | Add `_djiDevice` state, `useDjiSource()`, `clearDjiSource()`. |
| `LiveScreen` (modified) | Composable | Extend USB `BroadcastReceiver` to route DJI devices (VID `0x2CA3`) to `useDjiSource`. Add DJI badge case. |
| `SourceSheet` (modified) | Composable | Activate the previously greyed-out DJI Goggles row. |
| `usb_device_filter.xml` (modified) | Resource | Add DJI vendor-ID entry so the OS auto-launches the app on device attach. |

### Dependency graph

```
LiveScreen
    └── LiveViewModel
            └── DjiGogglesVideoSource
                    ├── DumlFramer          (pure Kotlin, no deps)
                    └── MediaCodec          (Android-native, API 16+)
```

`DumlFramer` has no Android dependencies and can be tested on the JVM without Robolectric or an emulator.

---

## DUML Packet Format

All multi-byte integer fields are little-endian.

```
Offset  Size   Field
------  -----  -----
0       1      SOF — always 0x55
1       1      Length low byte  (total packet length including all fields)
2       1      Length high 2 bits [1:0] | Version in bits [7:2]
3       1      CRC8 of bytes [0..2]  — polynomial 0x31, init 0x77
4       1      Sender component ID
5       1      Receiver component ID
6       2      Sequence number
8       1      Attributes  (bit 7 = need_ack, bit 6 = is_ack, bits [2:0] = msg_type)
9       1      Command set
10      1      Command ID
11..N-3 var    Payload (may be zero-length)
N-2     2      CRC16 of bytes [0..N-3] — polynomial 0x1021, init 0xFFFF
```

Minimum packet size: 13 bytes (11-byte fixed header + 0-byte payload + 2-byte CRC16).

### CRC algorithms

**CRC8** (header integrity, bytes [0..2]):
- Polynomial: 0x31 (Maxim/Dallas 1-Wire)
- Initial value: 0x77
- No input/output reflection

**CRC16** (full-packet integrity):
- Polynomial: 0x1021 (CRC-16/CCITT)
- Initial value: 0xFFFF
- No input/output reflection

Both are implemented as lookup-table functions in `DumlFramer` for performance.

### Known component IDs

| ID    | Component |
|-------|-----------|
| 0x01  | Flight Controller |
| 0x02  | Video Transmitter / O3 module |
| 0x03  | Remote Controller |
| 0x06  | Mobile App / Ground Station |
| 0x07  | Goggles |

DroneEdge identifies itself as **0x06** (Mobile App). The goggles are addressed as **0x07**.

### Video streaming startup sequence

The following DUML commands are sent in order after the USB interface is claimed. Command IDs are based on community reverse-engineering of the Avata + Goggles 2 firmware and may require tuning on first hardware contact.

| Step | Cmd Set | Cmd ID | Purpose |
|------|---------|--------|---------|
| 1    | 0x00    | 0x00   | Ping / connection handshake |
| 2    | 0x09    | 0x09   | Request video stream start |

Each command is sent with `need_ack = true`. If no ACK is received within 2 seconds the source emits an error and closes the connection gracefully. Constants are isolated in a companion object so they can be updated without structural changes.

---

## `DumlFramer` API

```kotlin
class DumlFramer {
    /** Feed raw bytes from a bulk read. Returns a parsed DumlPacket when a
     *  complete, CRC-valid packet has been assembled; null otherwise.
     *  Resyncs automatically on bad magic or CRC failure. */
    fun feed(bytes: ByteArray, length: Int): DumlPacket?

    /** Build a ready-to-send DUML packet with correct length, CRC8, and CRC16. */
    fun buildPacket(
        src: Int, dst: Int, seq: Int,
        cmdSet: Int, cmdId: Int,
        payload: ByteArray = byteArrayOf(),
        needAck: Boolean = false,
    ): ByteArray

    /** Clear accumulated inbound state. Call after a connection reset. */
    fun reset()
}

data class DumlPacket(
    val src: Int,
    val dst: Int,
    val seq: Int,
    val attributes: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
)
```

---

## `DjiGogglesVideoSource` Behaviour

### Lifecycle

```
start() called
    │
    ├── Find bulk streaming interface  (VID 0x2CA3, scan interface classes)
    ├── Open UsbDeviceConnection
    ├── Claim interface
    ├── Send DUML startup sequence
    │       └── Await ACK (2 s timeout → error + close)
    ├── Decode loop (IO coroutine)
    │       ├── bulkTransfer(endpoint, buf, 16 KB, 1 000 ms)
    │       ├── DumlFramer.feed()  → DumlPacket?
    │       ├── If video packet → append NAL unit to frame buffer
    │       ├── If frame complete → MediaCodec.queueInputBuffer()
    │       ├── MediaCodec.dequeueOutputBuffer() → YUV frame
    │       ├── YUV → Bitmap (YuvImage path)
    │       └── emit(VideoFrame(...))
    └── stop() called → running = false → finally: codec.stop/release, connection.close
```

### H.264 decoding

- MediaCodec is configured for `"video/avc"`.
- Initial `MediaFormat` uses hardcoded dimensions 1280×720. When `INFO_OUTPUT_FORMAT_CHANGED` is received, `width` and `height` properties are updated to reflect the actual decoded dimensions.
- SPS and PPS NAL units are forwarded to MediaCodec as `BUFFER_FLAG_CODEC_CONFIG`.
- The decoder is created once per `start()` call and released in the `finally` block — it is never reused across sessions.

### Error handling

All errors are non-fatal. The source calls `stop()` on itself and surfaces a human-readable message via `LiveViewModel.reportError()`. The app returns to IDLE state; the user can retry. Specific error cases:

| Condition | Message shown |
|---|---|
| No DJI interface found on device | "DJI device connected but no streaming interface found" |
| USB permission not granted | "USB permission denied for DJI Goggles" |
| DUML handshake timeout | "DJI Goggles did not respond — is a drone connected and powered on?" |
| MediaCodec configuration failure | "Could not initialise H.264 decoder" |
| Sustained bulk transfer errors (>10 consecutive) | "DJI video stream lost" |

---

## UI Changes

### `usb_device_filter.xml`

```xml
<usb-device vendor-id="11427" />   <!-- DJI: 0x2CA3 decimal = 11427 -->
```

Added alongside the existing UVC class entry. The OS will offer to launch DroneEdge when DJI Goggles are attached.

### `LiveScreen` — USB BroadcastReceiver

The existing receiver is extended with a VID check:

```
USB_DEVICE_ATTACHED received
    │
    ├── VID == 0x2CA3  → request DJI permission → useDjiSource()
    └── otherwise      → existing UVC path → useUsbSource()
```

Source badge gains a third case: `djiDevice != null → "DJI: <productName>"`.

### `SourceSheet`

`SourceChoice` enum gains a `DJI` value: `{ CAMERA, USB, FILE, DJI, FAKE }`. The existing DJI Goggles row (currently rendered without an `onClick`) gains `onClick = { onSelect(SourceChoice.DJI) }` and `active = activeChoice == SourceChoice.DJI`. The `activeSourceChoice` derived value in `LiveScreen` gains a `djiDevice != null → SourceChoice.DJI` case.

Tapping the active DJI Goggles row:
1. Scans `UsbManager.deviceList` for VID `0x2CA3`.
2. If not found: snackbar "No DJI Goggles detected — connect via USB and power on the drone".
3. If found and permission already granted: `useDjiSource()`.
4. If found but permission not yet granted: `requestPermission()` → handled by the existing BroadcastReceiver.

### Source mutual exclusion

`useDjiSource()`, `useUsbSource()`, `useCameraSource()`, `useFileSource()`, and `useFakeSource()` each clear all other source state fields (`_djiDevice`, `_usbDevice`, `_cameraFacing`, `_videoUri`) before setting their own. Only one source is active at a time.

---

## Security Considerations

### USB permission model

Android requires explicit user consent before any app may open a USB device. DroneEdge never opens a DJI device without first receiving `EXTRA_PERMISSION_GRANTED = true` from the system's permission broadcast. The `PendingIntent` used for permission requests is created with `FLAG_IMMUTABLE` to prevent intent redirection by third-party apps.

### Spoofed USB devices

A malicious USB device could claim DJI's VID (`0x2CA3`) to trigger the permission dialog. Mitigations:

- **CRC validation** — `DumlFramer` rejects any packet whose CRC8 or CRC16 does not match. Malformed input cannot reach the MediaCodec or `LiveViewModel`.
- **Length bounds checking** — packets whose declared length exceeds the actual bytes read are discarded immediately, preventing out-of-bounds reads.
- **No sensitive data exposure** — DroneEdge is a passive observer. The only data transmitted over USB is the DUML startup handshake. No credentials, tokens, or user data are sent to the device.
- **Graceful degradation** — if the connected device does not respond to the DUML handshake within 2 seconds, the source closes cleanly and reports an error. It does not retry indefinitely.

### DUML command scope

DroneEdge sends exactly two DUML commands (ping and stream-start). It never sends flight-control command sets (cmd_set `0x03`, `0x05`). This constraint is enforced by having no code paths that construct those command sets — it is a structural restriction, not a runtime check.

### Resource cleanup

`UsbDeviceConnection` and `MediaCodec` are always released in `finally` blocks, ensuring USB interfaces are freed even if the coroutine is cancelled or an exception is thrown. A leaked USB interface could block other apps (including DJI Fly) from connecting to the goggles.

---

## File Map

| Action | Path |
|--------|------|
| Create | `app/src/main/java/com/yotam/droneedge/video/DumlFramer.kt` |
| Create | `app/src/main/java/com/yotam/droneedge/video/DjiGogglesVideoSource.kt` |
| Create | `app/src/test/java/com/yotam/droneedge/video/DumlFramerTest.kt` |
| Modify | `app/src/main/res/xml/usb_device_filter.xml` |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt` |

---

## Test Plan

### Automated (no hardware required)

| Test class | Coverage |
|---|---|
| `DumlFramerTest` | Single-packet parse; multi-chunk assembly; bad magic resync; CRC8 failure → null; CRC16 failure → null; truncated packet → null; `buildPacket` round-trip; sequence number increment |

### Build gate

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass.

### Manual smoke test — emulator / no drone

1. Launch app on emulator.
2. Open Source Sheet — DJI Goggles row is active (not greyed out).
3. Tap DJI Goggles — snackbar: "No DJI Goggles detected — connect via USB and power on the drone".
4. All other source/session flows unaffected.

### Manual integration test — physical hardware *(when tablet available)*

1. Power on Avata drone and Goggles.
2. Connect Goggles to tablet via USB-C OTG.
3. OS permission dialog appears — grant.
4. Source badge updates to "DJI: \<product name\>".
5. Tap Start — preview FPS counter rises, H.264 frames visible.
6. Tap Stop — session ends cleanly, codec released.
7. Unplug Goggles — badge reverts to "FAKE SOURCE", snackbar: "DJI video stream lost".

---

## Open Questions / Tuning Notes

- **DUML command IDs** — cmd_set `0x09`, cmd_id `0x09` for stream-start is based on community research for Avata + Goggles 2. These constants are isolated in `DjiGogglesVideoSource.Companion` and will likely need adjustment on first hardware contact.
- **NAL unit framing** — the exact DUML cmd_set/cmd_id that carries H.264 video payloads is not confirmed without a USB capture. A diagnostic logging mode (controlled by a `BuildConfig.DEBUG` guard) will dump the first 16 bytes of every received DUML packet to Logcat to aid protocol identification.
- **Goggles Integra vs Goggles 2** — both share VID `0x2CA3` but may use different command sequences. If they differ, the startup sequence will be parameterised by a `GogglesModel` enum detected from the USB product ID.
