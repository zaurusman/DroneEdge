package com.yotam.droneedge.detection

import com.yotam.droneedge.video.VideoFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDetectorTest {

    private val detector = FakeDetector()

    private fun frame(timestampMs: Long = 0L) = VideoFrame(
        index       = 0L,
        timestampMs = timestampMs,
        width       = 1280,
        height      = 720,
    )

    @Test
    fun `detect returns two detections`() = runTest {
        val results = detector.detect(frame())
        assertEquals(2, results.size)
    }

    @Test
    fun `detect labels are person and drone`() = runTest {
        val labels = detector.detect(frame()).map { it.label }
        assertTrue("person" in labels)
        assertTrue("drone" in labels)
    }

    @Test
    fun `detect confidence values are in 0 to 1 range`() = runTest {
        detector.detect(frame()).forEach { det ->
            assertTrue("${det.label} confidence out of range", det.confidence in 0f..1f)
        }
    }

    @Test
    fun `bounding boxes are normalized within 0 to 1`() = runTest {
        detector.detect(frame()).forEach { det ->
            val box = det.boundingBox
            assertTrue("${det.label} left < 0",  box.left   >= 0f)
            assertTrue("${det.label} top < 0",   box.top    >= 0f)
            assertTrue("${det.label} right > 1", box.right  <= 1f)
            assertTrue("${det.label} bottom > 1",box.bottom <= 1f)
            assertTrue("${det.label} left >= right",  box.left < box.right)
            assertTrue("${det.label} top >= bottom",  box.top  < box.bottom)
        }
    }

    @Test
    fun `bounding boxes change with timestamp`() = runTest {
        val early = detector.detect(frame(timestampMs = 0L))
        val later = detector.detect(frame(timestampMs = 2_000L))
        // Boxes driven by sin/cos should differ at t=0 vs t=2s
        val earlyPerson = early.first { it.label == "person" }.boundingBox
        val laterPerson = later.first { it.label == "person" }.boundingBox
        assertTrue(
            "Expected box position to change over time",
            earlyPerson.left != laterPerson.left || earlyPerson.top != laterPerson.top
        )
    }
}
