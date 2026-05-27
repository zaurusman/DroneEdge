package com.yotam.droneedge.video

import kotlinx.coroutines.flow.Flow

/**
 * Replaceable source of video frames.
 *
 * Implementations: FakeVideoSource, FileReplayVideoSource, CameraVideoSource,
 * UsbUvcVideoSource, DjiGogglesVideoSource.
 */
interface VideoSource {
    /** Cold flow that emits frames while the source is running. */
    val frames: Flow<VideoFrame>

    /** Prepare and start emitting frames. */
    fun start()

    /** Signal the source to stop. The [frames] flow will complete shortly after. */
    fun stop()
}
