package com.yotam.droneedge.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * @param targetFps Upper bound on emission rate; actual rate may be lower due to decoding time.
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

    override val width: Int get() = videoWidth
    override val height: Int get() = videoHeight

    @Volatile private var running = false
    @Volatile private var frameIndex = 0L

    override val frames: Flow<VideoFrame> = flow {
        val intervalMs = 1000L / targetFps
        var videoTimeMs = 0L

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        try {
            while (running) {
                val bitmap: Bitmap? = retriever.getFrameAtTime(
                    videoTimeMs * 1_000L,                        // µs
                    MediaMetadataRetriever.OPTION_CLOSEST,       // decode actual frame, not just nearest keyframe
                )
                emit(
                    VideoFrame(
                        index       = frameIndex++,
                        timestampMs = videoTimeMs,
                        width       = videoWidth,
                        height      = videoHeight,
                        bitmap      = bitmap,
                    )
                )
                videoTimeMs += intervalMs
                if (durationMs > 0L && videoTimeMs >= durationMs) videoTimeMs = 0L
                delay(intervalMs)
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO) // bitmap decoding stays off the main thread

    override fun start() {
        frameIndex = 0L
        running = true
    }

    override fun stop() {
        running = false
    }
}
