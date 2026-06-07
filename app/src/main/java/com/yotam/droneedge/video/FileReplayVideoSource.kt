package com.droneedge.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Emits VideoFrames from a local MP4 file.
 *
 * Each frame includes a [VideoFrame.bitmap] decoded via [MediaMetadataRetriever.getFrameAtTime].
 * This is accurate but slower than MediaCodec; it is adequate for Phase 3 development and
 * will be replaced with a proper MediaCodec decoder in Phase 5.
 *
 * The flow runs on [Dispatchers.IO] so that bitmap decoding does not block the main thread.
 * Because decoding each frame can take > 33 ms, the effective FPS is limited by decoder speed.
 * The ViewModel uses the latest detection result to keep the overlay live.
 *
 * @param uri       Content URI of the video file chosen by the user.
 * @param context   Application context — needed by MediaMetadataRetriever.
 * @param targetFps Unused beyond documentation; actual rate is bounded by decode throughput.
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
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Use the first decoded frame's actual pixel dimensions instead of metadata —
            // getFrameAtTime() on API 27+ already applies rotation metadata, so the bitmap
            // dimensions reflect the display orientation. No additional rotation is needed.
            val sampleFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val metaW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val metaH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            videoWidth  = sampleFrame?.width  ?: metaW
            videoHeight = sampleFrame?.height ?: metaH
            sampleFrame?.recycle()
        } finally {
            retriever.release()
        }
    }

    override val width: Int get() = videoWidth
    override val height: Int get() = videoHeight

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        // Start timing after setup so the first seek is at position ~0.
        // videoTimeMs = wall-clock elapsed → source video advances at 1× real-time speed
        // regardless of decode throughput. If getFrameAtTime() takes 150ms, the next seek
        // jumps 150ms forward in source time, so content plays at 1× speed even at 6 fps.
        var videoStartWallMs = System.currentTimeMillis()
        var videoLoopOffsetMs = 0L

        try {
            while (running) {
                val wallElapsed = System.currentTimeMillis() - videoStartWallMs
                var videoTimeMs = videoLoopOffsetMs + wallElapsed

                if (durationMs > 0L && videoTimeMs >= durationMs) {
                    videoStartWallMs  = System.currentTimeMillis()
                    videoLoopOffsetMs = 0L
                    videoTimeMs       = 0L
                }

                val bitmap: Bitmap? = retriever.getFrameAtTime(
                    videoTimeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )

                emit(
                    VideoFrame(
                        index       = frameIndex++,
                        timestampMs = System.currentTimeMillis(),
                        width       = videoWidth,
                        height      = videoHeight,
                        bitmap      = bitmap,
                    )
                )
                // No artificial delay — loop runs at full decode speed.
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO)

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }
}
