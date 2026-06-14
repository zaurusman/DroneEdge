package com.droneedge.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.droneedge.app.video.VideoFrame
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device object detector backed by a TensorFlow Lite model.
 *
 * When [modelFile] is provided it is loaded from the filesystem (e.g. external storage pushed
 * via ADB); otherwise [modelFileName] is loaded from assets. Labels are resolved similarly:
 * a sidecar "<model-name>.txt" next to [modelFile] takes priority, falling back to [labelsFileName]
 * in assets.
 *
 * Inference is always called from [Dispatchers.Default] by [LiveViewModel] — not thread-safe.
 */
class TfliteDetector(
    context: Context,
    modelFileName: String = "detect.tflite",
    private val labelsFileName: String = "labelmap.txt",
    var confidenceThreshold: Float = 0.5f,
    modelFile: File? = null,
) : Detector, Closeable {

    private val appContext: Context = context.applicationContext

    // Use the actual file name from disk when loading externally; fall back to asset name.
    private val modelFileName: String = modelFile?.name ?: modelFileName

    private val delegate: Closeable?   // NnApiDelegate or GpuDelegate; null = CPU
    private val interpreter: Interpreter
    val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputDataType: DataType
    private val outputParser: DetectionOutputParser

    var delegateName: String = "CPU"
        private set
    private var gpuFailureReason: String? = null
    val modelInfo: String get() = "$delegateName ${inputWidth}×${inputHeight}"

    // Inference timing for the first 20 frames, written once to tflite_timing.txt.
    private var inferenceCount = 0
    private val timingLines = mutableListOf<String>()

    // Pre-allocated to avoid per-frame heap pressure on the inference hot path.
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val scaledBitmap: Bitmap
    private val scalingCanvas: Canvas
    private val scalingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        val model = if (modelFile != null) loadModelFromFile(modelFile)
                    else loadModelFile(context, modelFileName)
        val (interp, del) = buildInterpreter(model)
        interpreter = interp
        delegate    = del
        labels = loadLabelsForModel(context, modelFile, labelsFileName)

        // Read input shape and dtype directly from the model — works for any size or dtype.
        // Expected shape: [batch, height, width, channels]
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        inputHeight   = inputShape[1]
        inputWidth    = inputShape[2]
        inputDataType = inputTensor.dataType()

        // Auto-select output parser: YOLO if single 3-D output tensor, SSD otherwise.
        outputParser = if (interpreter.outputTensorCount == 1) {
            val outShape = interpreter.getOutputTensor(0).shape() // e.g. [1, 5, 8400]
            if (outShape.size == 3) YoloOutputParser(numAnchors = outShape[2], numValues = outShape[1])
            else SsdOutputParser()
        } else {
            SsdOutputParser()
        }

        val bytesPerChannel = if (inputDataType == DataType.FLOAT32) 4 else 1
        inputBuffer  = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel)
            .apply { order(ByteOrder.nativeOrder()) }
        pixels       = IntArray(inputWidth * inputHeight)
        scaledBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        scalingCanvas = Canvas(scaledBitmap)
        dstRect.set(0, 0, inputWidth, inputHeight)

        // Write delegate info to the public logs folder so it's visible on the USB drive.
        // Values are escaped (newlines → <NL>) so each log entry is exactly one line.
        runCatching {
            val logDir = com.droneedge.app.MainActivity.droneEdgeLogsDir().also { it.mkdirs() }
            val lines = buildList {
                add("build=v9-flex-gpu")  // bump this tag each new APK so we know which one ran
                add("model=$modelFileName")
                add("delegate=$delegateName")
                add("input=${inputWidth}x${inputHeight} $inputDataType")
                gpuFailureReason?.let { add("gpuError=${it.replace("\n", "<NL>")}") }
            }
            File(logDir, "tflite_delegate.txt").printWriter().use { pw ->
                lines.forEach { pw.println(it) }
            }
        }
    }

    override suspend fun detect(frame: VideoFrame): List<Detection> {
        val bitmap = frame.bitmap ?: return emptyList()

        fillInputBuffer(bitmap)
        val outputs = outputParser.allocateOutputs(maxDetections = 10)
        val outputMap = HashMap<Int, Any>(outputs.size).also { map ->
            outputs.forEachIndexed { i, o -> map[i] = o }
        }

        val t0 = android.os.SystemClock.elapsedRealtime()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        recordTiming(android.os.SystemClock.elapsedRealtime() - t0)

        return outputParser.parse(outputs, labels, confidenceThreshold)
    }

    private fun recordTiming(ms: Long) {
        val n = ++inferenceCount
        if (n <= 20) {
            timingLines += "[${n}] ${ms}ms"
        }
        // Write after every 5th inference (overwrite), so data exists even for short runs.
        if (n % 5 == 0 || n == 1) {
            runCatching {
                val logDir = com.droneedge.app.MainActivity.droneEdgeLogsDir().also { it.mkdirs() }
                File(logDir, "tflite_timing.txt").writeText(
                    "delegate=$delegateName  n=$n\n${timingLines.joinToString("\n")}\n"
                )
            }
        }
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
        scaledBitmap.recycle()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds the interpreter, mirroring the proven Sirena setup:
     *   1. GPU delegate — the fast path on Adreno 750 / Mali. The standard org.tensorflow
     *      tensorflow-lite-gpu artifact bundles the real libtensorflowlite_gpu_jni.so (unlike
     *      the LiteRT 1.0.1 stub that failed). With tensorflow-lite-select-tf-ops on the
     *      classpath, the Flex delegate auto-loads (via reflection in NativeInterpreterWrapper)
     *      to run any ops the GPU/builtin op set can't — this is what lets the YOLO model
     *      run on GPU without op-unsupported failures.
     *   2. CPU fallback — multi-threaded (used on emulators with no usable GPU, and as a
     *      safety net if GPU init throws). Flex still auto-loads here too.
     */
    private fun buildInterpreter(model: MappedByteBuffer): Pair<Interpreter, Closeable?> {
        // 1. GPU delegate. Allow FP16 precision + quantized models so FP16-quantized weights work.
        run {
            val gpu = runCatching {
                GpuDelegate(GpuDelegate.Options().apply {
                    setPrecisionLossAllowed(true)
                    setQuantizedModelsAllowed(true)
                })
            }.getOrElse { e ->
                gpuFailureReason = "gpuNew:${e.message?.take(120) ?: e.javaClass.simpleName}"
                null
            } ?: return@run
            model.rewind()
            runCatching {
                Interpreter(model, Interpreter.Options().apply { addDelegate(gpu) }) to gpu
            }.onSuccess {
                Log.i(TAG, "inference: GPU")
                delegateName = "GPU"
                return it
            }.onFailure { e ->
                gpu.close()
                gpuFailureReason = "interpInit:${e.message?.take(300) ?: e.javaClass.simpleName}"
            }
        }

        // 2. CPU fallback — 4 threads (balances throughput vs thermal on the tablet).
        model.rewind()
        Log.i(TAG, "inference: CPU (4 threads)")
        delegateName = "CPU"
        return Interpreter(model, Interpreter.Options().apply { numThreads = 4 }) to null
    }

    /**
     * Memory-map the model file so the interpreter can read it directly without copying.
     * The file must not be compressed in the APK — enforced by noCompress("tflite") in build.gradle.kts.
     */
    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val fd = context.assets.openFd(fileName)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength,
        )
    }

    private fun loadModelFromFile(file: File): MappedByteBuffer =
        FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

    private fun loadLabelsForModel(context: Context, modelFile: File?, fallbackFileName: String): List<String> {
        if (modelFile != null) {
            val sidecar = File(modelFile.parent, "${modelFile.nameWithoutExtension}.txt")
            if (sidecar.exists()) return sidecar.bufferedReader().readLines()
        }
        return context.assets.open(fallbackFileName).bufferedReader().readLines()
    }

    private companion object { private const val TAG = "TfliteDetector" }

    // Scales [bitmap] into the pre-allocated scaledBitmap and packs pixels into inputBuffer.
    // Zero heap allocations on the hot inference path.
    private fun fillInputBuffer(bitmap: Bitmap) {
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        scalingCanvas.drawBitmap(bitmap, srcRect, dstRect, scalingPaint)
        scaledBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBuffer.rewind()
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8)  and 0xFF
            val b =  pixel         and 0xFF
            if (inputDataType == DataType.FLOAT32) {
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            } else {
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            }
        }
        inputBuffer.rewind()
    }
}
