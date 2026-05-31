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

// LifecycleOwner must be current at construction time.
// Re-instantiate (via useCameraSource) after configuration changes.
class CameraVideoSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val facing: Int = CameraSelector.LENS_FACING_BACK,
) : VideoSource {

    @Volatile override var width: Int = 1280
        private set
    @Volatile override var height: Int = 720
        private set

    @Volatile private var frameIndex = 0L

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
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { ia ->
                ia.setAnalyzer(executor) { proxy ->
                    try {
                        val bmp = proxy.toBitmap()
                        val w = bmp.width
                        val h = bmp.height
                        width = w
                        height = h
                        trySend(
                            VideoFrame(
                                index       = frameIndex++,
                                timestampMs = System.currentTimeMillis(),
                                width       = w,
                                height      = h,
                                bitmap      = bmp,
                            )
                        )
                    } finally {
                        proxy.close()
                    }
                }
            }

        val selector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        withContext(Dispatchers.Main) {
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }

        awaitClose {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                provider.unbind(analysis)
                executor.shutdown()
            }
        }
    }
}
