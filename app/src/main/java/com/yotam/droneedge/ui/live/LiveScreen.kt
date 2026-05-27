package com.yotam.droneedge.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yotam.droneedge.detection.Detection

@Composable
fun LiveScreen(vm: LiveViewModel = viewModel()) {
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val detections   by vm.detections.collectAsStateWithLifecycle()
    val previewFps   by vm.previewFps.collectAsStateWithLifecycle()
    val inferenceFps by vm.inferenceFps.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)) // dark background simulating a video feed
    ) {
        // ── Detection overlay ────────────────────────────────────────────────
        DetectionOverlay(
            detections = detections,
            modifier = Modifier.fillMaxSize(),
        )

        // ── FPS HUD (top-right) ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            HudText("Preview   ${"%.1f".format(previewFps)} fps")
            HudText("Inference ${"%.1f".format(inferenceFps)} fps")
        }

        // ── Session state badge (top-left) ───────────────────────────────────
        Text(
            text = sessionState.name,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            color = when (sessionState) {
                SessionState.RUNNING  -> Color(0xFF00E676)
                SessionState.STOPPING -> Color(0xFFFFAB00)
                SessionState.IDLE     -> Color(0xFF757575)
            },
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )

        // ── Start / Stop button (bottom-center) ──────────────────────────────
        Button(
            onClick = {
                when (sessionState) {
                    SessionState.IDLE    -> vm.start()
                    SessionState.RUNNING -> vm.stop()
                    SessionState.STOPPING -> Unit
                }
            },
            enabled = sessionState != SessionState.STOPPING,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
        ) {
            Text(
                text = when (sessionState) {
                    SessionState.IDLE     -> "Start"
                    SessionState.RUNNING  -> "Stop"
                    SessionState.STOPPING -> "Stopping…"
                }
            )
        }
    }
}

// ── Detection overlay ────────────────────────────────────────────────────────

private val boxColor     = Color(0xFF00E5FF)
private val labelBgColor = Color(0xCC000000)
private val labelStyle   = TextStyle(fontSize = 11.sp, color = Color.White)

@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val cw = size.width
        val ch = size.height

        detections.forEach { det ->
            val box = det.boundingBox
            val l = box.left   * cw
            val t = box.top    * ch
            val r = box.right  * cw
            val b = box.bottom * ch

            // Bounding box
            drawRect(
                color   = boxColor,
                topLeft = Offset(l, t),
                size    = Size(r - l, b - t),
                style   = Stroke(width = 3f),
            )

            // Label
            val labelText = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val measured  = textMeasurer.measure(labelText, style = labelStyle)
            val stripH    = measured.size.height.toFloat()

            drawRect(
                color   = labelBgColor,
                topLeft = Offset(l, t - stripH),
                size    = Size(maxOf(r - l, measured.size.width.toFloat()), stripH),
            )

            drawText(
                textLayoutResult = measured,
                topLeft          = Offset(l + 4f, t - stripH),
            )
        }
    }
}

// ── Small helpers ────────────────────────────────────────────────────────────

@Composable
private fun HudText(text: String) {
    Text(
        text = text,
        color = Color(0xFFE0E0E0),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
