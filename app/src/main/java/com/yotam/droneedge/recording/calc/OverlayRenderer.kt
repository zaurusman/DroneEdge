package com.droneedge.app.recording.calc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import com.droneedge.app.detection.Detection

/**
 * Renders bounding boxes + labels into a reusable transparent ARGB bitmap, matching the live
 * overlay / recorder style (FieldAccent orange). The bitmap is uploaded to a GL texture and
 * composited over the decoded video frame by [FrameCompositor].
 */
class OverlayRenderer(private val width: Int, private val height: Int) {

    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val boxPaint = Paint().apply {
        color = 0xFFF97316.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (3f * (height / 1080f)).coerceAtLeast(2f)
        isAntiAlias = false
    }
    private val labelBgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = (22f * (height / 1080f)).coerceAtLeast(12f)
        isAntiAlias = true
    }
    private val stripH = labelPaint.textSize * 1.5f

    /** Clears the bitmap and draws [detections]; returns the same bitmap instance. */
    fun render(detections: List<Detection>): Bitmap {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = width.toFloat()
        val h = height.toFloat()
        for (d in detections) {
            val l = d.boundingBox.left * w
            val t = d.boundingBox.top * h
            val r = d.boundingBox.right * w
            val b = d.boundingBox.bottom * h
            canvas.drawRect(l, t, r, b, boxPaint)
            val label = "${d.label} ${"%.0f".format(d.confidence * 100)}%"
            val lw = labelPaint.measureText(label)
            canvas.drawRect(l, t - stripH, l + lw + 8f, t, labelBgPaint)
            canvas.drawText(label, l + 4f, t - labelPaint.textSize * 0.35f, labelPaint)
        }
        return bitmap
    }

    fun release() = bitmap.recycle()
}
