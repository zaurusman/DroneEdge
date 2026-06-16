package com.droneedge.app.video

import android.content.Context
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.emptyFlow

/**
 * Decodes a local video file with MediaExtractor + MediaCodec, rendering to a display
 * Surface (full-res, GPU, auto-rotated) and capturing full-resolution frames via PixelCopy
 * for inference + recording. Replaces the ~6 fps MediaMetadataRetriever path.
 */
class FileMediaCodecVideoSource(
    private val uri: Uri,
    private val context: Context,
    private val renderSurface: Surface? = null,
) : VideoSource {

    @Volatile override var width: Int = 0
        private set
    @Volatile override var height: Int = 0
        private set

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override fun start() { frameIndex = 0L; running = true }
    override fun stop()  { running = false }

    // frames flow added in Task 2.
    override val frames: kotlinx.coroutines.flow.Flow<VideoFrame> = emptyFlow()

    companion object {
        /** Display dimensions after applying the file's rotation metadata. */
        fun rotatedDimensions(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
            if (rotation == 90 || rotation == 270) height to width else width to height

        /** Milliseconds to wait so the frame at [presentationTimeUs] renders at 1× real time. */
        fun pacingDelayMs(
            anchorWallNs: Long,
            anchorPtsUs: Long,
            presentationTimeUs: Long,
            nowNs: Long,
        ): Long {
            val targetWallNs = anchorWallNs + (presentationTimeUs - anchorPtsUs) * 1_000L
            return (targetWallNs - nowNs) / 1_000_000L
        }
    }
}
