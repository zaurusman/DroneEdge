package com.yotam.droneedge.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits VideoFrames timed to the duration of a local MP4 file.
 *
 * Phase 2: frames carry only metadata (index, timestamp, dimensions) — no pixel data yet.
 * Actual bitmap extraction will be added in Phase 3 when TFLite needs raw pixels.
 *
 * The emitted [VideoFrame.timestampMs] advances by [1000 / targetFps] ms per frame and
 * loops back to 0 when it reaches the video's duration, matching the ExoPlayer loop.
 *
 * @param uri       Content URI of the video file chosen by the user.
 * @param context   Application context (needed by MediaMetadataRetriever).
 * @param targetFps Frame rate at which to drive the detection pipeline.
 */
class FileReplayVideoSource(
    private val uri: Uri,
    private val context: Context,
    private val targetFps: Int = 30,
) : VideoSource {

    val videoWidth: Int
    val videoHeight: Int
    val durationMs: Long

    init {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            videoWidth  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1280
            videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 720
            durationMs  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val intervalMs = 1000L / targetFps
        var videoTimeMs = 0L

        while (running) {
            emit(
                VideoFrame(
                    index        = frameIndex++,
                    timestampMs  = videoTimeMs,
                    width        = videoWidth,
                    height       = videoHeight,
                )
            )
            videoTimeMs += intervalMs
            if (durationMs > 0L && videoTimeMs >= durationMs) videoTimeMs = 0L
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
