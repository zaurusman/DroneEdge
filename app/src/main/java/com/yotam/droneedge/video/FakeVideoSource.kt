package com.droneedge.app.video

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits synthetic VideoFrames at a fixed frame rate with no real pixel data.
 * Used during Phase 1 development before any real video source is available.
 */
class FakeVideoSource(
    private val frameWidthPx: Int = 1280,
    private val frameHeightPx: Int = 720,
    private val targetFps: Int = 30,
) : VideoSource {

    override val width: Int get() = frameWidthPx
    override val height: Int get() = frameHeightPx

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val intervalMs = 1000L / targetFps
        while (running) {
            val idx = frameIndex++
            emit(
                VideoFrame(
                    index       = idx,
                    timestampMs = System.currentTimeMillis(),
                    width       = frameWidthPx,
                    height      = frameHeightPx,
                )
            )
            delay(intervalMs)
        }
    }

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }
}
