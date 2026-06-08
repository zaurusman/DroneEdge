package com.droneedge.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.droneedge.app.video.VideoFrame
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
    private val modelFileName: String = "detect.tflite",
    private val labelsFileName: String = "labelmap.txt",
    val confidenceThreshold: Float = 0.5f,
    modelFile: File? = null,
) : Detector, Closeable {

    private val delegate: Closeable?   // NnApiDelegate or GpuDelegate; null = CPU
    private val interpreter: Interpreter
    val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputDataType: DataType
    private val outputParser: DetectionOutputParser

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
    }

    override suspend fun detect(frame: VideoFrame): List<Detection> {
        val bitmap = frame.bitmap ?: return emptyList()

        fillInputBuffer(bitmap)
        val outputs = outputParser.allocateOutputs(maxDetections = 10)
        val outputMap = HashMap<Int, Any>(outputs.size).also { map ->
            outputs.forEachIndexed { i, o -> map[i] = o }
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        return outputParser.parse(outputs, labels, confidenceThreshold)
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
        scaledBitmap.recycle()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Tries delegates in priority order for this device:
     *   1. NNAPI  — routes to Qualcomm Hexagon DSP/NPU (best on Snapdragon)
     *   2. GPU    — Adreno via OpenGL ES (Adreno 750 on Tab S10+)
     *   3. CPU    — 4 threads (always works)
     *
     * The winning delegate is stored so it can be closed when the detector is closed.
     */
    private fun buildInterpreter(model: MappedByteBuffer): Pair<Interpreter, Closeable?> {
        // 1. NNAPI — available API 28+ (our minSdk), routes to Qualcomm Hexagon DSP/NPU
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                model.rewind()
                val nnApi = NnApiDelegate()
                val opts  = Interpreter.Options().apply { addDelegate(nnApi) }
                Interpreter(model, opts) to nnApi
            }.onSuccess {
                Log.i(TAG, "inference: NNAPI (Hexagon DSP/NPU)")
                return it
            }.onFailure { Log.w(TAG, "NNAPI unavailable: ${it.message}") }
        }

        // 2. GPU delegate — Adreno 750 via OpenGL ES
        runCatching {
            model.rewind()
            val compat = CompatibilityList()
            val supported = compat.isDelegateSupportedOnThisDevice
            compat.close()
            if (supported) {
                val gpu  = GpuDelegate()
                val opts = Interpreter.Options().apply { addDelegate(gpu) }
                Interpreter(model, opts) to gpu
            } else null
        }.getOrNull()?.let {
            Log.i(TAG, "inference: GPU delegate (Adreno)")
            return it
        }

        // 3. CPU fallback
        model.rewind()
        Log.i(TAG, "inference: CPU (4 threads)")
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
