package com.yotam.droneedge.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    // Cycle through distinct solid colors so the recorded video is easy to verify.
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN)
    private val paint = Paint()

    private fun makeFrame(index: Long): Bitmap {
        val bmp = Bitmap.createBitmap(frameWidthPx, frameHeightPx, Bitmap.Config.ARGB_8888)
        paint.color = colors[(index % colors.size).toInt()]
        Canvas(bmp).drawRect(0f, 0f, frameWidthPx.toFloat(), frameHeightPx.toFloat(), paint)
        return bmp
    }

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
                    bitmap      = makeFrame(idx),
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
