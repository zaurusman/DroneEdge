package com.yotam.droneedge.video

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraVideoSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val facing: Int = CameraSelector.LENS_FACING_BACK,
) : VideoSource {

    override var width: Int = 1280
        private set
    override var height: Int = 720
        private set

    private var frameIndex = 0L

    override fun start() {
        frameIndex = 0L
    }

    // stop() is a no-op — cleanup is owned by the flow's awaitClose,
    // which fires automatically when pipelineJob?.cancel() is called in LiveViewModel.stop().
    override fun stop() = Unit

    override val frames: Flow<VideoFrame> = callbackFlow {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try { cont.resume(future.get()) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                },
                context.mainExecutor,
            )
        }

        val executor = Executors.newSingleThreadExecutor()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { ia ->
                ia.setAnalyzer(executor) { proxy ->
                    val bmp = proxy.toBitmap()
                    width  = bmp.width
                    height = bmp.height
                    trySend(
                        VideoFrame(
                            index       = frameIndex++,
                            timestampMs = System.currentTimeMillis(),
                            width       = bmp.width,
                            height      = bmp.height,
                            bitmap      = bmp,
                        )
                    )
                    proxy.close()
                }
            }

        val selector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        withContext(Dispatchers.Main) {
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }

        awaitClose {
            provider.unbind(analysis)
            executor.shutdown()
        }
    }
}
