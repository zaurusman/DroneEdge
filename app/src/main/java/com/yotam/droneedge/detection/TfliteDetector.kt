package com.yotam.droneedge.detection

import android.content.Context
import android.graphics.Bitmap
import com.yotam.droneedge.video.VideoFrame
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device object detector backed by a TensorFlow Lite model.
 *
 * Loads [modelFileName] and [labelsFileName] from the app's assets folder at construction time.
 * Inference is always called from [Dispatchers.Default] by [LiveViewModel] — this class is
 * not thread-safe; do not share it across threads.
 *
 * Returns an empty list when [VideoFrame.bitmap] is null (e.g. FakeVideoSource frames).
 *
 * @param outputParser  Pluggable output parser — default is [SsdOutputParser] which handles
 *                      SSD MobileNet V1/V2 and EfficientDet-Lite model output formats.
 */
class TfliteDetector(
    context: Context,
    private val modelFileName: String = "detect.tflite",
    private val labelsFileName: String = "labelmap.txt",
    private val outputParser: DetectionOutputParser = SsdOutputParser(),
    private val inputWidth: Int = 300,
    private val inputHeight: Int = 300,
    val confidenceThreshold: Float = 0.5f,
) : Detector, Closeable {

    private val interpreter: Interpreter
    val labels: List<String>

    init {
        val model = loadModelFile(context, modelFileName)
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(model, options)
        labels = loadLabels(context, labelsFileName)
    }

    override suspend fun detect(frame: VideoFrame): List<Detection> {
        val bitmap = frame.bitmap ?: return emptyList()

        val inputBuffer = bitmapToByteBuffer(bitmap, inputWidth, inputHeight)
        val outputs = outputParser.allocateOutputs(maxDetections = 10)
        val outputMap = HashMap<Int, Any>(outputs.size).also { map ->
            outputs.forEachIndexed { i, o -> map[i] = o }
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        return outputParser.parse(outputs, labels, confidenceThreshold)
    }

    override fun close() = interpreter.close()

    // ── Private helpers ───────────────────────────────────────────────────────

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

    private fun loadLabels(context: Context, fileName: String): List<String> =
        context.assets.open(fileName).bufferedReader().readLines()

    /**
     * Scale [bitmap] to [width]×[height] and pack RGB pixels into a direct [ByteBuffer].
     * Quantized uint8 models expect raw byte values (0–255); no normalisation needed.
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val buffer = ByteBuffer.allocateDirect(width * height * 3).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            buffer.put(((pixel shr 8)  and 0xFF).toByte()) // G
            buffer.put((pixel          and 0xFF).toByte()) // B
        }
        buffer.rewind()
        return buffer
    }
}
