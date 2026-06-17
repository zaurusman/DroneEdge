package com.droneedge.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMediaCodecVideoSourceTest {

    @Test
    fun rotatedDimensions_keepsDimensionsForUprightRotations() {
        assertEquals(1920 to 1080, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 0))
        assertEquals(1920 to 1080, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 180))
    }

    @Test
    fun rotatedDimensions_swapsDimensionsForSidewaysRotations() {
        assertEquals(1080 to 1920, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 90))
        assertEquals(1080 to 1920, FileMediaCodecVideoSource.rotatedDimensions(1920, 1080, 270))
    }

    @Test
    fun pacingDelayMs_returnsRemainingMillisUntilPresentationTime() {
        val delay = FileMediaCodecVideoSource.pacingDelayMs(
            anchorWallNs = 1_000_000_000L,
            anchorPtsUs = 0L,
            presentationTimeUs = 100_000L,
            nowNs = 1_040_000_000L,
        )
        assertEquals(60L, delay)
    }

    @Test
    fun pacingDelayMs_negativeWhenBehindSchedule() {
        val delay = FileMediaCodecVideoSource.pacingDelayMs(
            anchorWallNs = 1_000_000_000L,
            anchorPtsUs = 0L,
            presentationTimeUs = 10_000L,
            nowNs = 1_050_000_000L,
        )
        assertEquals(-40L, delay)
    }
}
