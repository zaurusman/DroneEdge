package com.droneedge.app.recording

import com.droneedge.app.detection.BoundingBox
import com.droneedge.app.detection.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEventSerializationTest {

    @Test
    fun singleDetectionContainsExpectedFields() {
        val event = DetectionEvent(
            frameIndex = 42L,
            timestampMs = 1000L,
            detections = listOf(
                Detection("drone", 0.9f, BoundingBox(0.1f, 0.2f, 0.5f, 0.6f))
            ),
        )
        val json = event.toJson()
        assertTrue(json.contains(""""frameIndex":42"""))
        assertTrue(json.contains(""""timestampMs":1000"""))
        assertTrue(json.contains(""""label":"drone""""))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertFalse(json.contains("\n"))
    }

    @Test
    fun emptyDetectionsProducesValidJson() {
        val json = DetectionEvent(0L, 0L, emptyList()).toJson()
        assertTrue(json.contains(""""detections":[]"""))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun multipleDetectionsSeparatedByCommas() {
        val event = DetectionEvent(
            frameIndex = 1L,
            timestampMs = 33L,
            detections = listOf(
                Detection("a", 0.8f, BoundingBox(0f, 0f, 0.5f, 0.5f)),
                Detection("b", 0.6f, BoundingBox(0.5f, 0.5f, 1f, 1f)),
            ),
        )
        val json = event.toJson()
        assertTrue(json.contains(""""label":"a""""))
        assertTrue(json.contains(""""label":"b""""))
    }
}
