# Phase 5: USB/UVC Video Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `UsbUvcVideoSource` — a `VideoSource` implementation that streams MJPEG frames from a standard UVC-class USB camera via Android's USB Host API, and wire it into the existing Live screen pipeline.

**Architecture:** `UvcFrameAssembler` handles UVC bulk-transfer payload parsing (pure Kotlin, unit-testable). `UsbUvcVideoSource` wraps it behind the existing `VideoSource` interface — same cold-Flow contract as `FakeVideoSource` and `FileReplayVideoSource`. `LiveViewModel` gains `useUsbSource()` / `clearUsbSource()`, and `LiveScreen` gains a USB badge, a Connect/Clear button, and a dynamically registered `BroadcastReceiver` for USB attach/detach/permission events.

**Tech Stack:** Android USB Host API (`android.hardware.usb.*`), `BitmapFactory.decodeByteArray` for MJPEG→Bitmap, Kotlin Flow / `flowOn(Dispatchers.IO)`, JUnit 4 for unit tests. **No new Gradle dependencies needed.**

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `app/src/main/java/com/yotam/droneedge/video/UvcFrameAssembler.kt` | UVC payload header parsing + MJPEG frame reassembly |
| Create | `app/src/main/java/com/yotam/droneedge/video/UsbUvcVideoSource.kt` | `VideoSource` impl: USB open, interface claim, bulk loop, MJPEG decode |
| Create | `app/src/main/res/xml/usb_device_filter.xml` | Declares which USB device classes auto-launch the activity |
| Create | `app/src/test/java/com/yotam/droneedge/video/UvcFrameAssemblerTest.kt` | Unit tests for assembler (no hardware required) |
| Modify | `app/src/main/AndroidManifest.xml` | `uses-feature` USB host; activity `USB_DEVICE_ATTACHED` intent-filter + meta-data |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` | `_usbDevice` state, `useUsbSource()`, `clearUsbSource()`, `reportError()` |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` | USB badge, Connect/Clear button, BroadcastReceiver `DisposableEffect` |

---

## Task 1: Create branch

**Files:** none (git only)

- [ ] **Step 1: Create feature branch**

```bash
git checkout main && git pull
git checkout -b feature/usb-uvc-source
```

---

## Task 2: USB Manifest Setup

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/usb_device_filter.xml`

- [ ] **Step 1: Create `usb_device_filter.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
        Match USB Video Class devices that advertise the Video class at the
        device level (bDeviceClass = 0x0E). Many multi-function webcams report
        0xEF at device level with UVC at the interface level — those are found
        in code by scanning interface classes after connection.
    -->
    <usb-device class="14" />
</resources>
```

Save to `app/src/main/res/xml/usb_device_filter.xml`.

- [ ] **Step 2: Update `AndroidManifest.xml`**

Replace the full manifest with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools" >

    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <!-- Declares that this app requires USB host mode hardware -->
    <uses-feature android:name="android.hardware.usb.host" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.DroneEdge" >
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.DroneEdge" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Launch app when a matching USB device is attached -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_device_filter" />
        </activity>
    </application>

</manifest>
```

- [ ] **Step 3: Verify build compiles**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/usb_device_filter.xml
git commit -m "feat: add USB host feature and device-attached intent filter"
```

---

## Task 3: UvcFrameAssembler + Unit Tests

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/UvcFrameAssembler.kt`
- Create: `app/src/test/java/com/yotam/droneedge/video/UvcFrameAssemblerTest.kt`

### Background: UVC payload header format

Each bulk transfer packet starts with a UVC payload header:
- Byte 0: `bHeaderLength` (PHL) — total header length in bytes (≥ 2)
- Byte 1: `bmHeaderInfo` flags:
  - Bit 0: **FID** — Frame ID, toggles between 0 and 1 for each new frame
  - Bit 1: **EOF** — End of Frame; set on the last packet of a frame
  - Bit 6: **ERR** — Error; discard current frame accumulation

Payload data starts at byte `bHeaderLength`. A complete MJPEG frame is assembled by concatenating all payload chunks until EOF is set. If FID toggles before EOF, the previous partial frame is discarded.

- [ ] **Step 1: Write the failing tests first**

Create `app/src/test/java/com/yotam/droneedge/video/UvcFrameAssemblerTest.kt`:

```kotlin
package com.yotam.droneedge.video

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UvcFrameAssemblerTest {

    private lateinit var assembler: UvcFrameAssembler

    @Before fun setUp() { assembler = UvcFrameAssembler() }

    /** Build a minimal 2-byte header + payload packet. */
    private fun packet(fid: Int, eof: Boolean, payload: ByteArray): ByteArray {
        val bmInfo = (fid and 0x01) or (if (eof) 0x02 else 0x00)
        return byteArrayOf(0x02.toByte(), bmInfo.toByte()) + payload
    }

    @Test fun `returns null for packet shorter than 2 bytes`() {
        assertNull(assembler.feed(byteArrayOf(0x01), 1))
    }

    @Test fun `returns null when header length exceeds packet length`() {
        // PHL=5 but only 3 bytes total
        val pkt = byteArrayOf(0x05, 0x02.toByte(), 0x01)
        assertNull(assembler.feed(pkt, pkt.size))
    }

    @Test fun `returns null for incomplete frame (no EOF)`() {
        val pkt = packet(fid = 0, eof = false, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertNull(assembler.feed(pkt, pkt.size))
    }

    @Test fun `returns payload bytes when EOF set on single packet`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAB.toByte())
        val pkt  = packet(fid = 0, eof = true, payload = jpeg)
        val result = assembler.feed(pkt, pkt.size)
        assertNotNull(result)
        assertArrayEquals(jpeg, result)
    }

    @Test fun `accumulates multi-packet frame then returns complete bytes on EOF`() {
        val part1 = byteArrayOf(0x01, 0x02, 0x03)
        val part2 = byteArrayOf(0x04, 0x05, 0x06)
        assertNull(assembler.feed(packet(fid = 0, eof = false, payload = part1), part1.size + 2))
        val result = assembler.feed(packet(fid = 0, eof = true, payload = part2), part2.size + 2)
        assertNotNull(result)
        assertArrayEquals(part1 + part2, result)
    }

    @Test fun `FID toggle discards previous partial frame, returns new frame on EOF`() {
        val partial = packet(fid = 0, eof = false, payload = byteArrayOf(0x01, 0x02))
        assembler.feed(partial, partial.size)
        val newPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val pkt = packet(fid = 1, eof = true, payload = newPayload)
        val result = assembler.feed(pkt, pkt.size)
        assertNotNull(result)
        assertArrayEquals(newPayload, result)
    }

    @Test fun `ERR bit triggers reset and returns null`() {
        val partial = packet(fid = 0, eof = false, payload = byteArrayOf(0x01))
        assembler.feed(partial, partial.size)
        // ERR bit (0x40) set in bmHeaderInfo
        val errPkt = byteArrayOf(0x02, 0x40.toByte())
        assertNull(assembler.feed(errPkt, errPkt.size))
        // Next frame starts fresh
        val fresh = byteArrayOf(0xCC.toByte())
        val result = assembler.feed(packet(fid = 0, eof = true, payload = fresh), fresh.size + 2)
        assertNotNull(result)
        assertArrayEquals(fresh, result)
    }

    @Test fun `reset clears accumulated state`() {
        assembler.feed(packet(fid = 0, eof = false, payload = byteArrayOf(0x01, 0x02)), 4)
        assembler.reset()
        val fresh = byteArrayOf(0xDD.toByte())
        val result = assembler.feed(packet(fid = 0, eof = true, payload = fresh), fresh.size + 2)
        assertNotNull(result)
        assertArrayEquals(fresh, result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.yotam.droneedge.video.UvcFrameAssemblerTest"
```

Expected: compilation error (class not found)

- [ ] **Step 3: Implement `UvcFrameAssembler`**

Create `app/src/main/java/com/yotam/droneedge/video/UvcFrameAssembler.kt`:

```kotlin
package com.yotam.droneedge.video

import java.io.ByteArrayOutputStream

class UvcFrameAssembler {

    private var lastFid: Int = -1
    private val buffer = ByteArrayOutputStream(512 * 1024)

    /**
     * Feed one raw USB bulk-transfer packet. Returns assembled MJPEG frame bytes
     * when the EOF bit is set on the final packet of a frame; null otherwise.
     *
     * Thread-unsafe — call only from the IO coroutine in UsbUvcVideoSource.
     */
    fun feed(packet: ByteArray, length: Int): ByteArray? {
        if (length < 2) return null
        val headerLen = packet[0].toInt() and 0xFF
        if (headerLen < 2 || headerLen > length) return null

        val bmHeaderInfo = packet[1].toInt() and 0xFF
        val fid  = bmHeaderInfo and 0x01
        val eof  = (bmHeaderInfo and 0x02) != 0
        val err  = (bmHeaderInfo and 0x40) != 0

        if (err) { reset(); return null }

        if (lastFid != -1 && fid != lastFid) {
            // Frame ID toggled before EOF — previous frame was corrupted; discard
            buffer.reset()
        }
        lastFid = fid

        val payloadLen = length - headerLen
        if (payloadLen > 0) buffer.write(packet, headerLen, payloadLen)

        return if (eof && buffer.size() > 0) {
            buffer.toByteArray().also { buffer.reset() }
        } else null
    }

    fun reset() {
        lastFid = -1
        buffer.reset()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.yotam.droneedge.video.UvcFrameAssemblerTest"
```

Expected: `BUILD SUCCESSFUL`, all 8 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/UvcFrameAssembler.kt \
        app/src/test/java/com/yotam/droneedge/video/UvcFrameAssemblerTest.kt
git commit -m "feat: add UvcFrameAssembler with unit tests for UVC payload reassembly"
```

---

## Task 4: UsbUvcVideoSource

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/UsbUvcVideoSource.kt`

### Background: UVC control request to start MJPEG stream

Before reading frames, the driver must commit stream parameters with a class-specific control transfer:
- `bmRequestType` = `0x21` (Class | Interface | Host→Device)
- `bRequest` = `0x01` (SET_CUR)
- `wValue` = `0x0200` (VS_COMMIT_CONTROL selector)
- `wIndex` = video streaming interface number
- `data` = 26-byte UVC 1.1 probe/commit structure specifying format index 1 (MJPEG), frame index 1, ~30 fps

This tells the camera which format/resolution/fps to stream. Most cameras default to MJPEG as format index 1 and their smallest frame as index 1. The format/frame indices are hardcoded to sensible defaults; if a specific camera needs different values the constants at the top of the class can be adjusted.

- [ ] **Step 1: Write the failing build gate test**

The `UsbUvcVideoSource` has no platform-free unit tests (it requires real USB hardware). We verify it compiles by running the debug build gate only. Skip this step to the build gate in Step 3.

- [ ] **Step 2: Implement `UsbUvcVideoSource`**

Create `app/src/main/java/com/yotam/droneedge/video/UsbUvcVideoSource.kt`:

```kotlin
package com.yotam.droneedge.video

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class UsbUvcVideoSource(
    private val context: Context,
    val device: UsbDevice,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L
    private val assembler = UvcFrameAssembler()

    override val frames: Flow<VideoFrame> = flow {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val info = findBulkStreamingInterface(device)
            ?: error("No UVC bulk streaming interface on ${device.deviceName}")
        val connection = usbManager.openDevice(device)
            ?: error("Cannot open ${device.deviceName} — USB permission not granted?")

        try {
            check(connection.claimInterface(info.iface, true)) {
                "Cannot claim UVC streaming interface"
            }
            commitMjpegStream(connection, info.ifaceNumber)
            assembler.reset()

            val buf = ByteArray(16_384)
            while (running) {
                val len = connection.bulkTransfer(info.endpoint, buf, buf.size, 1_000)
                if (len < 0) continue

                val jpeg = assembler.feed(buf, len) ?: continue
                val bmp  = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                width  = bmp.width
                height = bmp.height

                emit(
                    VideoFrame(
                        index       = frameIndex++,
                        timestampMs = System.currentTimeMillis(),
                        width       = bmp.width,
                        height      = bmp.height,
                        bitmap      = bmp,
                    )
                )
            }
        } finally {
            connection.releaseInterface(info.iface)
            connection.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }

    private data class StreamInfo(
        val iface: UsbInterface,
        val endpoint: UsbEndpoint,
        val ifaceNumber: Int,
    )

    private fun findBulkStreamingInterface(dev: UsbDevice): StreamInfo? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            // UVC Video Streaming: bInterfaceClass=0x0E, bInterfaceSubClass=0x02
            if (iface.interfaceClass == 0x0E && iface.interfaceSubclass == 0x02) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) return StreamInfo(iface, ep, iface.id)
                }
            }
        }
        return null
    }

    private fun commitMjpegStream(connection: UsbDeviceConnection, vsIfaceNumber: Int) {
        // 26-byte UVC 1.1 VS Probe/Commit control structure
        // Format index 1 = MJPEG (typical for UVC cameras), frame index 1, ~30 fps
        val ctrl = ByteArray(26).apply {
            this[0] = 0x01               // bmHint: negotiate dwFrameInterval
            this[2] = 0x01               // bFormatIndex (1 = first format, usually MJPEG)
            this[3] = 0x01               // bFrameIndex  (1 = first frame descriptor)
            val interval = 333_333       // dwFrameInterval in 100ns units (≈30 fps)
            this[4] = (interval         and 0xFF).toByte()
            this[5] = (interval.shr(8)  and 0xFF).toByte()
            this[6] = (interval.shr(16) and 0xFF).toByte()
            this[7] = (interval.shr(24) and 0xFF).toByte()
        }
        // bmRequestType=0x21 Class|Interface|H→D, bRequest=SET_CUR(0x01)
        // wValue=VS_COMMIT_CONTROL (selector 0x02 shifted to high byte = 0x0200)
        connection.controlTransfer(0x21, 0x01, 0x0200, vsIfaceNumber, ctrl, ctrl.size, 1_000)
    }
}
```

- [ ] **Step 3: Compile check**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/UsbUvcVideoSource.kt
git commit -m "feat: implement UsbUvcVideoSource using Android USB Host API with MJPEG/bulk"
```

---

## Task 5: LiveViewModel USB wiring

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add imports and `_usbDevice` state**

Add `android.hardware.usb.UsbDevice` import and add these fields after `_videoUri`:

```kotlin
import android.hardware.usb.UsbDevice
import com.yotam.droneedge.video.UsbUvcVideoSource

// In the class body, after the _videoUri block:
private val _usbDevice = MutableStateFlow<UsbDevice?>(null)
val usbDevice: StateFlow<UsbDevice?> = _usbDevice.asStateFlow()
```

- [ ] **Step 2: Add `useUsbSource`, `clearUsbSource`, and `reportError` methods**

Add after `useFakeSource()`:

```kotlin
fun useUsbSource(device: UsbDevice, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource  = UsbUvcVideoSource(context.applicationContext, device)
    _usbDevice.value = device
    _videoUri.value  = null
}

fun clearUsbSource() {
    if (_sessionState.value != SessionState.IDLE) return
    if (_usbDevice.value != null) {
        videoSource      = FakeVideoSource()
        _usbDevice.value = null
    }
}

fun reportError(message: String) {
    _error.value = message
}
```

- [ ] **Step 3: Update `useFakeSource` to also clear USB device**

Change the existing `useFakeSource()` body from:

```kotlin
fun useFakeSource() {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource = FakeVideoSource()
    _videoUri.value = null
}
```

to:

```kotlin
fun useFakeSource() {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource      = FakeVideoSource()
    _videoUri.value  = null
    _usbDevice.value = null
}
```

- [ ] **Step 4: Build + test gate**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "feat: add useUsbSource/clearUsbSource to LiveViewModel"
```

---

## Task 6: LiveScreen USB UI

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Add USB imports at top of LiveScreen.kt**

Add these imports (keep existing ones, add below `import android.net.Uri`):

```kotlin
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
```

- [ ] **Step 2: Add constant for USB permission action**

Add above the `LiveScreen` composable function:

```kotlin
private const val ACTION_USB_PERMISSION = "com.yotam.droneedge.USB_PERMISSION"
```

- [ ] **Step 3: Collect `usbDevice` state in `LiveScreen`**

Inside `LiveScreen`, after the existing `collectAsStateWithLifecycle` calls, add:

```kotlin
val usbDevice by vm.usbDevice.collectAsStateWithLifecycle()
```

- [ ] **Step 4: Add USB BroadcastReceiver `DisposableEffect`**

Add this block inside `LiveScreen`, just before the `Box(modifier = Modifier.fillMaxSize())`:

```kotlin
// Register for USB attach/detach/permission broadcasts for as long as the screen is visible.
DisposableEffect(Unit) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val permIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_USB_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (usbManager.hasPermission(device)) vm.useUsbSource(device, ctx)
                    else usbManager.requestPermission(device, permIntent)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) vm.useUsbSource(device, ctx)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> vm.clearUsbSource()
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        addAction(ACTION_USB_PERMISSION)
    }
    context.registerReceiver(receiver, filter)
    onDispose { context.unregisterReceiver(receiver) }
}
```

- [ ] **Step 5: Update the source badge (top-left Column)**

Replace the existing `if (videoUri != null) { ... } else { ... }` badge block:

```kotlin
// OLD:
if (videoUri != null) {
    Text(
        text     = "FILE: ${videoUri!!.lastPathSegment ?: "video"}",
        ...
    )
} else {
    HudText("FAKE SOURCE")
}
```

```kotlin
// NEW:
when {
    usbDevice != null -> Text(
        text     = "USB: ${usbDevice!!.productName ?: usbDevice!!.deviceName}",
        color    = Color(0xFFB0BEC5),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
    videoUri != null -> Text(
        text     = "FILE: ${videoUri!!.lastPathSegment ?: "video"}",
        color    = Color(0xFFB0BEC5),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
    else -> HudText("FAKE SOURCE")
}
```

- [ ] **Step 6: Add "USB Cam" / "Clear USB" button in the controls row**

In the bottom `Row` block, add after the `"Pick Video"` / `"Clear Video"` block (around line 232):

```kotlin
// USB camera connect / clear button (only while idle)
if (sessionState == SessionState.IDLE) {
    if (usbDevice == null) {
        OutlinedButton(onClick = {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val uvcDevice = usbManager.deviceList.values.firstOrNull { dev ->
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
        }) {
            Text("USB Cam")
        }
    } else {
        OutlinedButton(
            onClick = { vm.clearUsbSource() },
            colors  = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
        ) {
            Text("Clear USB")
        }
    }
}
```

- [ ] **Step 7: Build + test gate**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: add USB camera connect/clear button and broadcast receiver to LiveScreen"
```

---

## Task 7: Full build gate + PR

- [ ] **Step 1: Run full build and tests**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Install on device/emulator**

```bash
./gradlew installDebug
```

- [ ] **Step 3: Manual smoke test — emulator (no camera)**

1. Launch `DroneEdge` on emulator
2. Verify "USB Cam" button is visible in bottom controls when IDLE
3. Tap "USB Cam" — expect error snackbar: "No UVC camera found…"
4. Verify Start/Stop/Fake/File flows are unaffected
5. Verify build does not crash

- [ ] **Step 4: Manual smoke test — physical device with USB camera** *(if hardware available)*

1. Connect Android tablet to Mac with ADB; connect USB webcam to tablet via USB-C OTG adapter
2. Install with `./gradlew installDebug`
3. Tap "USB Cam" — OS permission dialog appears; grant
4. Badge updates to "USB: &lt;camera name&gt;"
5. Tap Start — preview FPS counter rises, frames visible
6. Tap Stop — session ends cleanly
7. Unplug camera — badge reverts to "FAKE SOURCE"

- [ ] **Step 5: Create PR**

```bash
gh pr create \
  --title "feat: Phase 5 — USB/UVC video source" \
  --body "$(cat <<'EOF'
## Summary
- Adds `UvcFrameAssembler`: pure-Kotlin UVC bulk-payload parser with 8 unit tests
- Adds `UsbUvcVideoSource`: `VideoSource` impl that streams MJPEG frames from a UVC-class USB camera via Android USB Host API (no new dependencies)
- Wires USB source into `LiveViewModel` (`useUsbSource`, `clearUsbSource`, `reportError`)
- Adds USB badge, Connect/Clear button, and BroadcastReceiver to `LiveScreen`
- Declares USB host feature in manifest; activity launches on USB device attach

## Test plan
- [ ] `./gradlew test` — all unit tests pass (including 8 new `UvcFrameAssemblerTest` cases)
- [ ] `./gradlew assembleDebug` — clean build
- [ ] Emulator: "USB Cam" button shows correct error when no camera connected
- [ ] Physical device + USB OTG webcam: permission dialog, badge, live frames, clean stop

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Verification

| Check | Command | Expected |
|-------|---------|----------|
| Unit tests | `./gradlew test` | 8 new `UvcFrameAssemblerTest` cases PASS |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Emulator smoke | Install + tap "USB Cam" | Error snackbar, no crash |
| Hardware E2E | USB OTG webcam | Frames stream, FPS counter rises |

---

## Notes & Known Constraints

- **Bulk transfer only**: Android's `UsbDeviceConnection.bulkTransfer()` is the only reliable transfer type on API 28. Isochronous transfers require API 31+. Cameras that only expose an isochronous streaming endpoint (no bulk alternative) will not work with this implementation. Most consumer USB webcams and many DJI accessories support bulk.
- **MJPEG assumed**: Format index 1 and frame index 1 are hardcoded. If a camera uses a different index order, adjust `bFormatIndex`/`bFrameIndex` in `UsbUvcVideoSource.commitMjpegStream()`.
- **Multi-function devices**: Cameras that report `bDeviceClass = 0xEF` at the device level (common for webcams with audio) are still found because `findBulkStreamingInterface()` scans interface classes, not device class.
- **DJI Goggles (Phase 6)**: DJI uses a proprietary protocol, not standard UVC. Phase 6 will add `DjiGogglesVideoSource` behind the same `VideoSource` interface — the Phase 5 foundation is not directly reused but proves the USB Host plumbing works.
