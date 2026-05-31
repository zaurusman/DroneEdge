package com.yotam.droneedge.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorModeTest {

    @Test
    fun `all DetectorMode values round-trip through name`() {
        DetectorMode.entries.forEach { mode ->
            assertEquals(mode, DetectorMode.valueOf(mode.name))
        }
    }

    @Test
    fun `unknown stored name falls back to FAKE`() {
        val stored = "UNKNOWN_FUTURE_MODEL"
        val result = runCatching { DetectorMode.valueOf(stored) }.getOrDefault(DetectorMode.FAKE)
        assertEquals(DetectorMode.FAKE, result)
    }

    @Test
    fun `empty stored name falls back to FAKE`() {
        val result = runCatching { DetectorMode.valueOf("") }.getOrDefault(DetectorMode.FAKE)
        assertEquals(DetectorMode.FAKE, result)
    }
}
