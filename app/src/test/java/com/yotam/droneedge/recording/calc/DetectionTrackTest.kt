package com.droneedge.app.recording.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackTest {

    private val lines = listOf(
        """{"sessionStart":1000}""",
        """{"frameIndex":0,"timestampMs":1100,"detections":[{"label":"person","confidence":0.9000,"left":0.1000,"top":0.2000,"right":0.3000,"bottom":0.4000}]}""",
        """{"frameIndex":5,"timestampMs":1300,"detections":[{"label":"car","confidence":0.8000,"left":0.5000,"top":0.5000,"right":0.6000,"bottom":0.7000}]}""",
    )

    @Test fun parsesSessionStart() {
        assertEquals(1000L, DetectionTrack.parse(lines).sessionStartMs)
    }

    @Test fun beforeFirstDetectionIsEmpty() {
        assertTrue(DetectionTrack.parse(lines).boxesAt(1050L).isEmpty())
    }

    @Test fun returnsLastKnownDetections() {
        val t = DetectionTrack.parse(lines)
        assertEquals("person", t.boxesAt(1200L).single().label)
        assertEquals("car", t.boxesAt(1300L).single().label)
        assertEquals("car", t.boxesAt(9999L).single().label)
    }

    @Test fun parsesBoundingBoxAndConfidence() {
        val d = DetectionTrack.parse(lines).boxesAt(1100L).single()
        assertEquals(0.9f, d.confidence, 1e-4f)
        assertEquals(0.1f, d.boundingBox.left, 1e-4f)
        assertEquals(0.4f, d.boundingBox.bottom, 1e-4f)
    }

    @Test fun emptyOrGarbageLinesAreIgnored() {
        val t = DetectionTrack.parse(listOf("", """{"sessionStart":0}""", "not json"))
        assertEquals(0L, t.sessionStartMs)
        assertTrue(t.boxesAt(100L).isEmpty())
    }
}
