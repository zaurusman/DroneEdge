package org.tensorflow.lite.gpu

/**
 * Stub that satisfies the class reference inside GpuDelegate().
 *
 * org.tensorflow:tensorflow-lite-gpu (and com.google.ai.edge.litert:litert-gpu) ships
 * GpuDelegate.Options extends GpuDelegateFactory.Options, but GpuDelegateFactory itself
 * is never included in any published AAR artifact. At runtime, new GpuDelegate() throws
 * NoClassDefFoundError for GpuDelegateFactory$Options.
 *
 * This stub provides the missing class with TFLite-default values so GpuDelegate()
 * can be instantiated and Interpreter init proceeds normally.
 */
open class GpuDelegateFactory {
    open class Options {
        open fun isPrecisionLossAllowed(): Boolean = false
        open fun areQuantizedModelsAllowed(): Boolean = true
        open fun getInferencePreference(): Int = 1    // FAST_SINGLE_ANSWER
        open fun getSerializationDir(): String? = null
        open fun getModelToken(): String? = null
        open fun getForceBackend(): GpuBackend = GpuBackend.UNSET

        enum class GpuBackend(private val v: Int) {
            UNSET(0), OPENCL(1), OPENGL(2);
            fun value(): Int = v
        }
    }
}
