# Phase 6: DJI Goggles Video Source — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `DjiGogglesVideoSource` — a `VideoSource` that streams H.264 video from DJI Goggles via USB-C OTG using the DJI DUML protocol, decoded with MediaCodec, wired into the existing Live screen pipeline.

**Architecture:** A pure-Kotlin `DumlFramer` handles DUML packet parsing and building (CRC8 + CRC16, fully unit-testable). `DjiGogglesVideoSource` wraps it behind the `VideoSource` interface — same cold-Flow contract as all other sources. `LiveViewModel` gains `useDjiSource` / `clearDjiSource`, and `LiveScreen` + `SourceSheet` activate the previously greyed-out DJI Goggles option.

**Tech Stack:** Android USB Host API (`android.hardware.usb.*`), `MediaCodec` / `android.media.Image` for H.264→YUV→Bitmap, Kotlin Flow / `flowOn(Dispatchers.IO)`, JUnit 4 for unit tests. **No new Gradle dependencies.**

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `app/src/main/java/com/yotam/droneedge/video/DumlFramer.kt` | DUML packet parser + builder; CRC8/CRC16 tables |
| Create | `app/src/main/java/com/yotam/droneedge/video/DjiGogglesVideoSource.kt` | `VideoSource` impl: USB open, DUML handshake, H.264 decode |
| Create | `app/src/test/java/com/yotam/droneedge/video/DumlFramerTest.kt` | Unit tests for framer (no hardware) |
| Modify | `app/src/main/res/xml/usb_device_filter.xml` | Add DJI vendor-ID 11427 (0x2CA3) |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt` | `_djiDevice` state, `useDjiSource`, `clearDjiSource`, mutual exclusion |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt` | Add `DJI` to `SourceChoice` enum; activate DJI row |
| Modify | `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt` | Route DJI VID in USB receiver; DJI badge + frame display |

---

## Task 1: Create branch

**Files:** none (git only)

- [ ] **Step 1: Create feature branch from main**

```bash
git checkout main && git pull
git checkout -b feature/dji-goggles-source
```

---

## Task 2: `DumlFramer` + Unit Tests (TDD)

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/DumlFramer.kt`
- Create: `app/src/test/java/com/yotam/droneedge/video/DumlFramerTest.kt`

### Background: DUML packet format

```
Offset  Size  Field
------  ----  -----
0       1     SOF = 0x55
1       1     Length low byte  (total packet length, all fields)
2       1     Length high 2 bits [1:0] | version in bits [7:2]
3       1     CRC8 of bytes [0..2]  — polynomial 0x31, init 0x77
4       1     Sender component ID
5       1     Receiver component ID
6       2     Sequence number (little-endian)
8       1     Attributes (bit 7 = need_ack, bit 6 = is_ack)
9       1     Command set
10      1     Command ID
11..N-3 var   Payload (0 or more bytes)
N-2     2     CRC16 of bytes [0..N-3] — polynomial 0x1021, init 0xFFFF
```

Minimum packet: 13 bytes (11-byte header + 0 payload + 2-byte CRC16).

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/yotam/droneedge/video/DumlFramerTest.kt`:

```kotlin
package com.yotam.droneedge.video

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DumlFramerTest {

    private lateinit var framer: DumlFramer

    @Before fun setUp() { framer = DumlFramer() }

    private fun pkt(
        src: Int = 0x06, dst: Int = 0x07, seq: Int = 0,
        cmdSet: Int = 0x00, cmdId: Int = 0x00,
        payload: ByteArray = byteArrayOf(), needAck: Boolean = false,
    ): ByteArray = DumlFramer().buildPacket(src, dst, seq, cmdSet, cmdId, payload, needAck)

    @Test fun `returns null when fewer than 4 bytes fed`() {
        assertNull(framer.feed(byteArrayOf(0x55, 0x0D, 0x00), 3))
    }

    @Test fun `returns null for truncated packet`() {
        val p = pkt()
        assertNull(framer.feed(p, p.size / 2))
    }

    @Test fun `resyncs past leading non-SOF bytes`() {
        val garbage = byteArrayOf(0x01, 0xAB.toByte(), 0x00)
        val p = pkt(seq = 7)
        val result = framer.feed(garbage + p, garbage.size + p.size)
        assertNotNull(result)
        assertEquals(7, result!!.seq)
    }

    @Test fun `returns null and resyncs on bad CRC8`() {
        val p = pkt(seq = 1).copyOf()
        p[3] = (p[3].toInt() xor 0xFF).toByte()
        assertNull(framer.feed(p, p.size))
        // After resync, a good packet is parseable
        val good = pkt(seq = 9)
        assertNotNull(framer.feed(good, good.size))
    }

    @Test fun `returns null and resyncs on bad CRC16`() {
        val p = pkt(seq = 2).copyOf()
        p[p.size - 1] = (p[p.size - 1].toInt() xor 0xFF).toByte()
        assertNull(framer.feed(p, p.size))
        val good = pkt(seq = 10)
        assertNotNull(framer.feed(good, good.size))
    }

    @Test fun `parses complete packet with no payload`() {
        val p = pkt(src = 0x06, dst = 0x07, seq = 42, cmdSet = 0x09, cmdId = 0x09, needAck = true)
        val r = framer.feed(p, p.size)
        assertNotNull(r); r!!
        assertEquals(0x06, r.src)
        assertEquals(0x07, r.dst)
        assertEquals(42,   r.seq)
        assertEquals(0x09, r.cmdSet)
        assertEquals(0x09, r.cmdId)
        assertTrue(r.payload.isEmpty())
        assertEquals(0x80, r.attributes and 0x80)
    }

    @Test fun `parses complete packet with payload`() {
        val data = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val p = pkt(cmdSet = 0x05, cmdId = 0x0A, payload = data)
        val r = framer.feed(p, p.size)
        assertNotNull(r)
        assertArrayEquals(data, r!!.payload)
    }

    @Test fun `assembles packet split across two feed calls`() {
        val p = pkt(seq = 11)
        val half = p.size / 2
        assertNull(framer.feed(p.copyOfRange(0, half), half))
        val r = framer.feed(p.copyOfRange(half, p.size), p.size - half)
        assertNotNull(r)
        assertEquals(11, r!!.seq)
    }

    @Test fun `buildPacket round-trips through feed`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val p = DumlFramer().buildPacket(0x06, 0x07, 55, 0x09, 0x05, payload, needAck = false)
        val r = framer.feed(p, p.size)
        assertNotNull(r); r!!
        assertEquals(0x06, r.src); assertEquals(0x07, r.dst)
        assertEquals(55, r.seq);   assertEquals(0x09, r.cmdSet)
        assertEquals(0x05, r.cmdId)
        assertArrayEquals(payload, r.payload)
    }

    @Test fun `two packets in one feed — second buffered and returned on next call`() {
        val p1 = pkt(seq = 1, cmdId = 0x01)
        val p2 = pkt(seq = 2, cmdId = 0x02)
        val r1 = framer.feed(p1 + p2, p1.size + p2.size)
        assertNotNull(r1); assertEquals(1, r1!!.seq)
        val r2 = framer.feed(byteArrayOf(), 0)
        assertNotNull(r2); assertEquals(2, r2!!.seq)
    }

    @Test fun `reset discards buffered partial packet`() {
        val p = pkt(seq = 0)
        framer.feed(p, p.size / 2)  // partial
        framer.reset()
        val fresh = pkt(seq = 77)
        val r = framer.feed(fresh, fresh.size)
        assertNotNull(r)
        assertEquals(77, r!!.seq)
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
./gradlew test --tests "com.yotam.droneedge.video.DumlFramerTest"
```

Expected: compilation error (`DumlFramer` not found)

- [ ] **Step 3: Implement `DumlFramer`**

Create `app/src/main/java/com/yotam/droneedge/video/DumlFramer.kt`:

```kotlin
package com.yotam.droneedge.video

import java.io.ByteArrayOutputStream

data class DumlPacket(
    val src: Int,
    val dst: Int,
    val seq: Int,
    val attributes: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DumlPacket) return false
        return src == other.src && dst == other.dst && seq == other.seq &&
               attributes == other.attributes && cmdSet == other.cmdSet &&
               cmdId == other.cmdId && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var h = src
        h = 31 * h + dst; h = 31 * h + seq
        h = 31 * h + cmdSet; h = 31 * h + cmdId
        return 31 * h + payload.contentHashCode()
    }
}

class DumlFramer {

    private val buffer = ByteArrayOutputStream(4096)

    /**
     * Feed raw bytes from one USB bulk read. Returns a parsed [DumlPacket] when a
     * complete, CRC-valid packet is in the buffer; null otherwise. Automatically
     * resyncs on bad magic byte, bad CRC8, or bad CRC16 by discarding the offending
     * SOF byte and searching for the next 0x55.
     *
     * Passing length=0 is valid — it drains any packet already buffered from prior
     * calls (useful when two packets arrived in one bulk read).
     */
    fun feed(bytes: ByteArray, length: Int): DumlPacket? {
        if (length > 0) buffer.write(bytes, 0, minOf(length, bytes.size))
        return tryParse()
    }

    private fun tryParse(): DumlPacket? {
        val buf = buffer.toByteArray()
        var offset = 0

        while (offset < buf.size) {
            if (buf[offset] != 0x55.toByte()) { offset++; continue }
            if (buf.size - offset < 4) break

            val totalLen = (buf[offset + 1].toInt() and 0xFF) or
                           ((buf[offset + 2].toInt() and 0x03) shl 8)
            if (totalLen < 13) { offset++; continue }

            if (computeCrc8(buf, offset, 3) != (buf[offset + 3].toInt() and 0xFF)) {
                offset++; continue
            }
            if (buf.size - offset < totalLen) break

            val crc16Actual   = computeCrc16(buf, offset, totalLen - 2)
            val crc16Expected = ((buf[offset + totalLen - 2].toInt() and 0xFF) or
                                ((buf[offset + totalLen - 1].toInt() and 0xFF) shl 8))
            if (crc16Actual != crc16Expected) { offset++; continue }

            val payloadLen = totalLen - 13
            val packet = DumlPacket(
                src        = buf[offset + 4].toInt() and 0xFF,
                dst        = buf[offset + 5].toInt() and 0xFF,
                seq        = (buf[offset + 6].toInt() and 0xFF) or
                             ((buf[offset + 7].toInt() and 0xFF) shl 8),
                attributes = buf[offset + 8].toInt() and 0xFF,
                cmdSet     = buf[offset + 9].toInt() and 0xFF,
                cmdId      = buf[offset + 10].toInt() and 0xFF,
                payload    = if (payloadLen > 0)
                                 buf.copyOfRange(offset + 11, offset + 11 + payloadLen)
                             else byteArrayOf(),
            )
            buffer.reset()
            val tail = offset + totalLen
            if (tail < buf.size) buffer.write(buf, tail, buf.size - tail)
            return packet
        }

        buffer.reset()
        if (offset < buf.size) buffer.write(buf, offset, buf.size - offset)
        return null
    }

    /** Build a ready-to-send DUML packet with correct CRC8 and CRC16. */
    fun buildPacket(
        src: Int, dst: Int, seq: Int,
        cmdSet: Int, cmdId: Int,
        payload: ByteArray = byteArrayOf(),
        needAck: Boolean = false,
    ): ByteArray {
        val totalLen = 13 + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0]  = 0x55.toByte()
        pkt[1]  = (totalLen and 0xFF).toByte()
        pkt[2]  = ((totalLen shr 8) and 0x03).toByte()
        pkt[3]  = computeCrc8(pkt, 0, 3).toByte()
        pkt[4]  = (src    and 0xFF).toByte()
        pkt[5]  = (dst    and 0xFF).toByte()
        pkt[6]  = (seq    and 0xFF).toByte()
        pkt[7]  = ((seq   shr 8) and 0xFF).toByte()
        pkt[8]  = (if (needAck) 0x80 else 0x00).toByte()
        pkt[9]  = (cmdSet and 0xFF).toByte()
        pkt[10] = (cmdId  and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(pkt, 11)
        val crc16 = computeCrc16(pkt, 0, totalLen - 2)
        pkt[totalLen - 2] = (crc16        and 0xFF).toByte()
        pkt[totalLen - 1] = ((crc16 shr 8) and 0xFF).toByte()
        return pkt
    }

    fun reset() { buffer.reset() }

    companion object {
        // CRC8: polynomial 0x31 (Maxim/Dallas 1-Wire), init 0x77, no reflection
        private val CRC8_TABLE = IntArray(256).also { t ->
            for (i in 0..255) {
                var c = i
                repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x31 else c shl 1 }
                t[i] = c and 0xFF
            }
        }

        fun computeCrc8(data: ByteArray, offset: Int, count: Int): Int {
            var crc = 0x77
            for (i in offset until offset + count)
                crc = CRC8_TABLE[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
            return crc
        }

        // CRC16: polynomial 0x1021 (CRC-16/CCITT-FALSE), init 0xFFFF, no reflection
        private val CRC16_TABLE = IntArray(256).also { t ->
            for (i in 0..255) {
                var c = i shl 8
                repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
                t[i] = c and 0xFFFF
            }
        }

        fun computeCrc16(data: ByteArray, offset: Int, count: Int): Int {
            var crc = 0xFFFF
            for (i in offset until offset + count)
                crc = ((crc shl 8) xor CRC16_TABLE[((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
            return crc
        }
    }
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
./gradlew test --tests "com.yotam.droneedge.video.DumlFramerTest"
```

Expected: `BUILD SUCCESSFUL`, 10 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/DumlFramer.kt \
        app/src/test/java/com/yotam/droneedge/video/DumlFramerTest.kt
git commit -m "feat: add DumlFramer with CRC8/CRC16 and unit tests for DUML packet framing"
```

---

## Task 3: `DjiGogglesVideoSource`

**Files:**
- Create: `app/src/main/java/com/yotam/droneedge/video/DjiGogglesVideoSource.kt`

- [ ] **Step 1: Create `DjiGogglesVideoSource.kt`**

```kotlin
package com.yotam.droneedge.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.yotam.droneedge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream

class DjiGogglesVideoSource(
    private val context: Context,
    val device: UsbDevice,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val info = findBulkInterface(device)
            ?: error("DJI device connected but no streaming interface found")
        val connection = usbManager.openDevice(device)
            ?: error("USB permission denied for DJI Goggles")

        val codec = try {
            MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                c.configure(fmt, null, null, 0)
                c.start()
            }
        } catch (e: Exception) {
            connection.close()
            error("Could not initialise H.264 decoder")
        }

        try {
            check(connection.claimInterface(info.iface, true)) {
                "Cannot claim DJI streaming interface"
            }
            sendStartupSequence(connection, info)

            val framer  = DumlFramer()
            val buf     = ByteArray(16_384)
            val bufInfo = MediaCodec.BufferInfo()
            var consecutiveErrors = 0

            while (running) {
                val len = connection.bulkTransfer(info.endpointIn, buf, buf.size, 1_000)
                if (len < 0) {
                    if (++consecutiveErrors >= 10) error("DJI video stream lost")
                    continue
                }
                consecutiveErrors = 0

                val packet = framer.feed(buf, len) ?: continue

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "DUML src=0x%02x dst=0x%02x cmdSet=0x%02x cmdId=0x%02x payLen=%d"
                        .format(packet.src, packet.dst, packet.cmdSet, packet.cmdId, packet.payload.size))
                    if (packet.payload.isNotEmpty()) {
                        val preview = packet.payload.take(16).joinToString(" ") { "0x%02x".format(it) }
                        Log.d(TAG, "  payload preview: $preview")
                    }
                }

                if (packet.cmdSet != VIDEO_CMD_SET || packet.cmdId != VIDEO_CMD_ID) continue
                val nal = packet.payload
                if (nal.isEmpty()) continue

                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex < 0) continue
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                inputBuf.clear()
                inputBuf.put(nal)
                val flags = if (isSpsPpsNal(nal)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                codec.queueInputBuffer(inputIndex, 0, nal.size, System.currentTimeMillis() * 1000L, flags)

                when (val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        width  = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    in 0..Int.MAX_VALUE -> {
                        val image = codec.getOutputImage(outIdx)
                        val bmp   = if (image != null) imageToBitmap(image, width, height) else null
                        image?.close()
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bmp != null) emit(VideoFrame(
                            index       = frameIndex++,
                            timestampMs = System.currentTimeMillis(),
                            width       = width,
                            height      = height,
                            bitmap      = bmp,
                        ))
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            connection.releaseInterface(info.iface)
            connection.close()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    private data class StreamInfo(
        val iface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint?,
        val ifaceNumber: Int,
    )

    private fun findBulkInterface(dev: UsbDevice): StreamInfo? {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn != null) return StreamInfo(iface, epIn, epOut, iface.id)
        }
        return null
    }

    private fun sendStartupSequence(
        connection: android.hardware.usb.UsbDeviceConnection,
        info: StreamInfo,
    ) {
        val epOut = info.endpointOut ?: return
        val framer = DumlFramer()
        val ping = framer.buildPacket(
            src = SRC_MOBILE_APP, dst = DST_GOGGLES, seq = 0,
            cmdSet = CMD_SET_GENERAL, cmdId = CMD_ID_PING, needAck = true,
        )
        connection.bulkTransfer(epOut, ping, ping.size, 2_000)
        val startStream = framer.buildPacket(
            src = SRC_MOBILE_APP, dst = DST_GOGGLES, seq = 1,
            cmdSet = CMD_SET_VIDEO, cmdId = CMD_ID_START_STREAM, needAck = true,
        )
        connection.bulkTransfer(epOut, startStream, startStream.size, 2_000)
    }

    private fun isSpsPpsNal(nal: ByteArray): Boolean {
        val offset = when {
            nal.size > 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size > 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> return false
        }
        val nalType = nal[offset].toInt() and 0x1F
        return nalType == 7 || nalType == 8
    }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap? {
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yRowStride    = yPlane.rowStride
            val uvRowStride   = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, row * w, w)
            }
            val uvH = h / 2; val uvW = w / 2
            for (row in 0 until uvH) {
                for (col in 0 until uvW) {
                    val src = row * uvRowStride + col * uvPixelStride
                    val dst = w * h + row * w + col * 2
                    vBuf.position(src); nv21[dst]     = vBuf.get()
                    uBuf.position(src); nv21[dst + 1] = uBuf.get()
                }
            }
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 90, out)
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.e(TAG, "YUV→Bitmap conversion failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DjiGogglesVideoSource"

        /** DJI USB vendor ID (0x2CA3 = 11427 decimal). Used in LiveScreen and LiveViewModel. */
        const val VENDOR_ID = 0x2CA3

        private const val SRC_MOBILE_APP      = 0x06
        private const val DST_GOGGLES         = 0x07
        private const val CMD_SET_GENERAL     = 0x00
        private const val CMD_ID_PING         = 0x00
        private const val CMD_SET_VIDEO       = 0x09
        private const val CMD_ID_START_STREAM = 0x09

        // Video data packets: cmdSet + cmdId that carry H.264 NAL units.
        // These are placeholder values based on community research.
        // Update after inspecting Logcat output on first hardware connection.
        const val VIDEO_CMD_SET = 0x09
        const val VIDEO_CMD_ID  = 0x00
    }
}
```

- [ ] **Step 2: Build check**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/video/DjiGogglesVideoSource.kt
git commit -m "feat: implement DjiGogglesVideoSource with DUML handshake and H.264 MediaCodec decoding"
```

---

## Task 4: `usb_device_filter.xml` + `LiveViewModel` wiring

**Files:**
- Modify: `app/src/main/res/xml/usb_device_filter.xml`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`

- [ ] **Step 1: Add DJI vendor ID to USB device filter**

In `app/src/main/res/xml/usb_device_filter.xml`, replace the entire file with:

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

    <!-- DJI Goggles 2 / Integra: vendor ID 0x2CA3 = 11427 decimal -->
    <usb-device vendor-id="11427" />
</resources>
```

- [ ] **Step 2: Update `LiveViewModel`**

In `app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt`, make the following changes:

**2a. Add import** (after the existing `UsbUvcVideoSource` import):

```kotlin
import com.yotam.droneedge.video.DjiGogglesVideoSource
```

**2b. Add `_djiDevice` state** (after the `_usbDevice` block, around line 57):

```kotlin
// ── DJI Goggles device (null = no DJI source) ────────────────────────────
private val _djiDevice = MutableStateFlow<UsbDevice?>(null)
val djiDevice: StateFlow<UsbDevice?> = _djiDevice.asStateFlow()
```

**2c. Add `useDjiSource` and `clearDjiSource`** (after `clearUsbSource()`, around line 140):

```kotlin
fun useDjiSource(device: UsbDevice, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = DjiGogglesVideoSource(context.applicationContext, device)
    _djiDevice.value    = device
    _videoUri.value     = null
    _usbDevice.value    = null
    _cameraFacing.value = null
}

fun clearDjiSource() {
    if (_djiDevice.value == null) return
    _djiDevice.value = null
    if (_sessionState.value == SessionState.IDLE) videoSource = FakeVideoSource()
}
```

**2d. Add `_djiDevice.value = null` to every other source method** to maintain mutual exclusion.

`useFileSource` — add `_djiDevice.value = null` after the existing lines:
```kotlin
fun useFileSource(uri: Uri, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = FileReplayVideoSource(uri, context.applicationContext)
    _videoUri.value     = uri
    _usbDevice.value    = null
    _cameraFacing.value = null
    _djiDevice.value    = null
}
```

`useFakeSource` — add `_djiDevice.value = null`:
```kotlin
fun useFakeSource() {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = FakeVideoSource()
    _videoUri.value     = null
    _usbDevice.value    = null
    _cameraFacing.value = null
    _djiDevice.value    = null
}
```

`useUsbSource` — add `_djiDevice.value = null`:
```kotlin
fun useUsbSource(device: UsbDevice, context: android.content.Context) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = UsbUvcVideoSource(context.applicationContext, device)
    _usbDevice.value    = device
    _videoUri.value     = null
    _cameraFacing.value = null
    _djiDevice.value    = null
}
```

`useCameraSource` — add `_djiDevice.value = null`:
```kotlin
fun useCameraSource(facing: Int, context: Context, lifecycleOwner: LifecycleOwner) {
    if (_sessionState.value != SessionState.IDLE) return
    videoSource         = CameraVideoSource(context.applicationContext, lifecycleOwner, facing)
    _cameraFacing.value = facing
    _videoUri.value     = null
    _usbDevice.value    = null
    _djiDevice.value    = null
}
```

**2e. Update `handleUsbLaunchIntent`** to route DJI devices:

Replace the existing `handleUsbLaunchIntent` body:

```kotlin
fun handleUsbLaunchIntent(intent: android.content.Intent, context: android.content.Context) {
    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
    } ?: return
    val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
    if (usbManager.hasPermission(device)) {
        if (device.vendorId == DjiGogglesVideoSource.VENDOR_ID) useDjiSource(device, context)
        else useUsbSource(device, context)
    }
}
```

- [ ] **Step 3: Build + test gate**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/usb_device_filter.xml \
        app/src/main/java/com/yotam/droneedge/ui/live/LiveViewModel.kt
git commit -m "feat: add useDjiSource/clearDjiSource to LiveViewModel and DJI vendor ID to USB filter"
```

---

## Task 5: `SourceSheet` + `LiveScreen` UI

**Files:**
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt`
- Modify: `app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt`

- [ ] **Step 1: Update `SourceSheet.kt`**

**1a.** Change the `SourceChoice` enum (line 27):

```kotlin
enum class SourceChoice { CAMERA, USB, FILE, DJI, FAKE }
```

**1b.** Replace the greyed-out DJI row (the block at lines 71–77):

```kotlin
SourceRow(
    label   = "DJI Goggles",
    active  = activeChoice == SourceChoice.DJI,
    enabled = true,
    onClick = { onSelect(SourceChoice.DJI) },
)
```

- [ ] **Step 2: Update `LiveScreen.kt`**

**2a.** Add import for `DjiGogglesVideoSource` (after existing video imports):

```kotlin
import com.yotam.droneedge.video.DjiGogglesVideoSource
```

**2b.** Add `private const val DJI_VENDOR_ID = DjiGogglesVideoSource.VENDOR_ID` below the existing `ACTION_USB_PERMISSION` constant (line 87):

```kotlin
private const val ACTION_USB_PERMISSION = "com.yotam.droneedge.USB_PERMISSION"
private const val DJI_VENDOR_ID = DjiGogglesVideoSource.VENDOR_ID
```

**2c.** Add `djiDevice` state collection inside `LiveScreen` (after `val cameraFacing` line, around line 105):

```kotlin
val djiDevice by vm.djiDevice.collectAsStateWithLifecycle()
```

**2d.** Replace the entire `BroadcastReceiver` `when (intent.action)` block (lines 147–159) with:

```kotlin
when (intent.action) {
    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
        if (device.vendorId == DJI_VENDOR_ID) {
            if (usbManager.hasPermission(device)) vm.useDjiSource(device, ctx)
            else usbManager.requestPermission(device, permIntent)
        } else {
            if (usbManager.hasPermission(device)) vm.useUsbSource(device, ctx)
            else usbManager.requestPermission(device, permIntent)
        }
    }
    ACTION_USB_PERMISSION -> {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (granted) {
            if (device.vendorId == DJI_VENDOR_ID) vm.useDjiSource(device, ctx)
            else vm.useUsbSource(device, ctx)
        }
    }
    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
        if (device.vendorId == DJI_VENDOR_ID) {
            vm.reportError("DJI Goggles disconnected")
            vm.clearDjiSource()
        } else {
            vm.reportError("USB camera disconnected")
            vm.clearUsbSource()
        }
    }
}
```

**2e.** Replace the `activeSourceChoice` and `sourceLabel` derived values (lines 175–186):

```kotlin
val activeSourceChoice: SourceChoice? = when {
    djiDevice    != null -> SourceChoice.DJI
    usbDevice    != null -> SourceChoice.USB
    cameraFacing != null -> SourceChoice.CAMERA
    videoUri     != null -> SourceChoice.FILE
    else                 -> null
}
val sourceLabel = when {
    djiDevice    != null -> "DJI"
    usbDevice    != null -> "USB"
    cameraFacing != null -> "Camera"
    videoUri     != null -> "File"
    else                 -> "No Source"
}
```

**2f.** Update the background `when` block (lines 194–205) to display DJI frames via `CameraFrameDisplay`:

```kotlin
when {
    videoUri != null -> VideoPlayer(
        uri       = videoUri!!,
        isPlaying = sessionState == SessionState.RUNNING,
        modifier  = Modifier.fillMaxSize(),
    )
    cameraFacing != null || djiDevice != null -> CameraFrameDisplay(
        frames   = vm.latestFrame,
        modifier = Modifier.fillMaxSize(),
    )
    else -> Box(modifier = Modifier.fillMaxSize().background(FieldBackground))
}
```

**2g.** Add `SourceChoice.DJI` handling inside the `onSelect` lambda of `SourceSheet` (after the `SourceChoice.USB` block, around line 351):

```kotlin
SourceChoice.DJI -> {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val djiDev = usbManager.deviceList.values.firstOrNull { dev ->
        dev.vendorId == DJI_VENDOR_ID
    }
    when {
        djiDev == null ->
            vm.reportError("No DJI Goggles detected — connect via USB and power on the drone")
        usbManager.hasPermission(djiDev) ->
            vm.useDjiSource(djiDev, context)
        else ->
            usbManager.requestPermission(
                djiDev,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
    }
}
```

- [ ] **Step 3: Build + test gate**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/yotam/droneedge/ui/live/SourceSheet.kt \
        app/src/main/java/com/yotam/droneedge/ui/live/LiveScreen.kt
git commit -m "feat: activate DJI Goggles in SourceSheet, wire USB receiver and frame display in LiveScreen"
```

---

## Task 6: Full build gate + PR

- [ ] **Step 1: Final build and tests**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass (including 10 new `DumlFramerTest` cases)

- [ ] **Step 2: Install on emulator**

```bash
./gradlew installDebug
```

- [ ] **Step 3: Manual smoke test — emulator (no drone)**

1. Launch `DroneEdge` on emulator
2. Tap Source button → Source Sheet opens
3. Verify "DJI Goggles" row is **active** (not greyed-out, no "coming soon" label)
4. Tap "DJI Goggles" → snackbar: "No DJI Goggles detected — connect via USB and power on the drone"
5. Verify Start/Stop/File/Camera/Fake flows are unaffected

- [ ] **Step 4: Create PR**

```bash
gh pr create \
  --title "feat: Phase 6 — DJI Goggles video source" \
  --body "$(cat <<'EOF'
## Summary
- Adds `DumlFramer`: pure-Kotlin DUML packet parser/builder with CRC8 (poly 0x31, init 0x77) and CRC16 (poly 0x1021, init 0xFFFF). 10 unit tests, no Android dependencies.
- Adds `DjiGogglesVideoSource`: `VideoSource` impl that opens DJI USB device (VID 0x2CA3), sends DUML startup sequence, assembles H.264 NAL units, and decodes via `MediaCodec` + `android.media.Image` → NV21 → Bitmap. No new Gradle dependencies.
- Wires DJI source into `LiveViewModel` (`useDjiSource`, `clearDjiSource`, mutual exclusion with all other sources).
- Activates the DJI Goggles row in `SourceSheet` (was "coming soon"). `LiveScreen` USB BroadcastReceiver routes by VID: DJI → `useDjiSource`, other → `useUsbSource`. DJI frames render via `CameraFrameDisplay`.
- USB device filter declares DJI vendor ID 11427 (0x2CA3) so the OS auto-launches the app on goggles attach.
- DEBUG-guarded Logcat dumps DUML packet headers to aid protocol tuning when hardware is available.

## Security
- DUML framer rejects packets with bad CRC8 or CRC16 — malformed USB input cannot reach MediaCodec or ViewModel.
- `PendingIntent` uses `FLAG_IMMUTABLE`. USB permission is required before any device is opened.
- All USB connections and MediaCodec instances released in `finally` blocks.
- App never sends flight-control DUML command sets (no code paths construct them).

## Test plan
- [ ] `./gradlew test` — 10 new `DumlFramerTest` cases PASS
- [ ] `./gradlew assembleDebug` — clean build
- [ ] Emulator: DJI Goggles row active in Source Sheet; tapping shows correct error snackbar
- [ ] Physical hardware (when tablet available): permission dialog, badge "DJI: <name>", live H.264 frames, clean stop

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Verification

| Check | Command | Expected |
|-------|---------|----------|
| Unit tests | `./gradlew test` | 10 `DumlFramerTest` cases PASS |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Emulator smoke | Install + tap DJI Goggles | Error snackbar, no crash |
| Hardware E2E | DJI Goggles + Avata via OTG | DUML handshake, H.264 decode, FPS counter rises |

---

## Tuning Notes (when hardware arrives)

1. **Identify video packet cmdSet/cmdId** — connect goggles + drone, run `./gradlew installDebug`, start session, inspect Logcat tag `DjiGogglesVideoSource`. Every received DUML packet is logged (DEBUG build only). Find the cmdSet/cmdId that carries large payloads starting with `0x00 0x00 0x00 0x01` (H.264 Annex B start code). Update `VIDEO_CMD_SET` and `VIDEO_CMD_ID` in `DjiGogglesVideoSource.Companion`.
2. **Tune startup sequence** — if handshake fails (no video after start), inspect ACK packets in Logcat and adjust `CMD_SET_VIDEO` / `CMD_ID_START_STREAM`.
3. **Goggles Integra vs Goggles 2** — if command sequences differ between models, add a `GogglesModel` enum detected from `device.productId` and branch the startup sequence accordingly.
