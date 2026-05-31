package com.yotam.droneedge.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdOutputParserTest {

    private val parser = SsdOutputParser(maxDetections = 10)
    private val labels = listOf("background", "person", "car", "dog")

    // ── allocateOutputs ───────────────────────────────────────────────────────

    @Test
    fun `allocateOutputs returns array of 4 elements`() {
        val outputs = parser.allocateOutputs(10)
        assertEquals(4, outputs.size)
    }

    @Test
    fun `allocateOutputs boxes have correct shape`() {
        val outputs = parser.allocateOutputs(10)
        @Suppress("UNCHECKED_CAST")
        val boxes = outputs[0] as Array<Array<FloatArray>>
        assertEquals(1, boxes.size)
        assertEquals(10, boxes[0].size)
        assertEquals(4, boxes[0][0].size)
    }

    @Test
    fun `allocateOutputs classes and scores are 1xN float arrays`() {
        val outputs = parser.allocateOutputs(5)
        @Suppress("UNCHECKED_CAST")
        val classes = outputs[1] as Array<FloatArray>
        @Suppress("UNCHECKED_CAST")
        val scores  = outputs[2] as Array<FloatArray>
        assertEquals(5, classes[0].size)
        assertEquals(5, scores[0].size)
    }

    // ── parse — happy path ────────────────────────────────────────────────────

    private fun makeOutputs(
        boxRows: List<FloatArray>,
        classIds: List<Float>,
        scores: List<Float>,
        count: Int = boxRows.size,
    ): Array<Any> {
        val n = 10
        val boxes   = Array(1) { Array(n) { FloatArray(4) } }
        val cls     = Array(1) { FloatArray(n) }
        val scr     = Array(1) { FloatArray(n) }
        val cnt     = FloatArray(1) { count.toFloat() }

        boxRows.forEachIndexed { i, row -> boxes[0][i] = row }
        classIds.forEachIndexed { i, c -> cls[0][i] = c }
        scores.forEachIndexed { i, s -> scr[0][i] = s }

        return arrayOf(boxes, cls, scr, cnt)
    }

    @Test
    fun `parse returns correct detection count`() {
        val outputs = makeOutputs(
            boxRows   = listOf(floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f)),
            classIds  = listOf(1f),
            scores    = listOf(0.9f),
        )
        val result = parser.parse(outputs, labels, confidenceThreshold = 0.5f)
        assertEquals(1, result.size)
    }

    @Test
    fun `parse maps label by class index`() {
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(0f, 0f, 0.5f, 0.5f)),
            classIds = listOf(1f),   // model output 1 + offset 1 = index 2 → "car"
            scores   = listOf(0.8f),
        )
        val det = parser.parse(outputs, labels, 0.5f).first()
        assertEquals("car", det.label)
    }

    @Test
    fun `parse maps SSD box format to BoundingBox correctly`() {
        // SSD layout: [ymin, xmin, ymax, xmax] → left=xmin, top=ymin, right=xmax, bottom=ymax
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f)),
            classIds = listOf(1f),
            scores   = listOf(0.7f),
        )
        val box = parser.parse(outputs, labels, 0.5f).first().boundingBox
        assertEquals(0.2f, box.left,   0.0001f)
        assertEquals(0.1f, box.top,    0.0001f)
        assertEquals(0.9f, box.right,  0.0001f)
        assertEquals(0.8f, box.bottom, 0.0001f)
    }

    @Test
    fun `parse confidence matches score`() {
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
            classIds = listOf(3f),
            scores   = listOf(0.63f),
        )
        val det = parser.parse(outputs, labels, 0.5f).first()
        assertEquals(0.63f, det.confidence, 0.0001f)
    }

    // ── parse — filtering ─────────────────────────────────────────────────────

    @Test
    fun `parse filters detections below confidence threshold`() {
        val outputs = makeOutputs(
            boxRows  = listOf(
                floatArrayOf(0f, 0f, 1f, 1f),  // score 0.8 — keep
                floatArrayOf(0f, 0f, 1f, 1f),  // score 0.3 — drop
            ),
            classIds = listOf(1f, 1f),
            scores   = listOf(0.8f, 0.3f),
            count    = 2,
        )
        val result = parser.parse(outputs, labels, 0.5f)
        assertEquals(1, result.size)
    }

    @Test
    fun `parse returns empty list when count is zero`() {
        val outputs = makeOutputs(
            boxRows  = emptyList(),
            classIds = emptyList(),
            scores   = emptyList(),
            count    = 0,
        )
        assertTrue(parser.parse(outputs, labels, 0.5f).isEmpty())
    }

    @Test
    fun `parse clamps bounding box coordinates to 0-1`() {
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(-0.1f, -0.2f, 1.3f, 1.5f)),
            classIds = listOf(1f),
            scores   = listOf(0.9f),
        )
        val box = parser.parse(outputs, labels, 0.5f).first().boundingBox
        assertEquals(0f, box.left,   0.0001f)
        assertEquals(0f, box.top,    0.0001f)
        assertEquals(1f, box.right,  0.0001f)
        assertEquals(1f, box.bottom, 0.0001f)
    }

    @Test
    fun `parse falls back to class index label when out of range`() {
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
            classIds = listOf(99f),   // model output 99 + offset 1 = index 100 → out of range
            scores   = listOf(0.9f),
        )
        val det = parser.parse(outputs, labels, 0.5f).first()
        assertEquals("class 100", det.label)
    }

    @Test
    fun `parse skips triple-question-mark placeholder labels`() {
        val labelsWithPlaceholder = listOf("background", "???", "car")
        val outputs = makeOutputs(
            boxRows  = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
            classIds = listOf(0f),   // model output 0 + offset 1 = index 1 → "???"
            scores   = listOf(0.9f),
        )
        val result = parser.parse(outputs, labelsWithPlaceholder, 0.5f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse respects count not array size`() {
        // Array has 2 rows but count says only 1 is valid
        val outputs = makeOutputs(
            boxRows  = listOf(
                floatArrayOf(0f, 0f, 0.5f, 0.5f),
                floatArrayOf(0f, 0f, 0.5f, 0.5f),
            ),
            classIds = listOf(1f, 1f),
            scores   = listOf(0.9f, 0.9f),
            count    = 1,
        )
        assertEquals(1, parser.parse(outputs, labels, 0.5f).size)
    }
}
