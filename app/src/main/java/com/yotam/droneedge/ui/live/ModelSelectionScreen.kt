package com.yotam.droneedge.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurfaceElevated
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary

@Composable
fun ModelSelectionScreen(
    initialMode: DetectorMode,
    isTfliteAvailable: Boolean,
    onConfirm: (DetectorMode) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initialMode) }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(FieldBackground)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text       = "DRONEEDGE",
            color      = FieldAccent,
            fontSize   = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text          = "SELECT DETECTION MODEL",
            color         = FieldTextMuted,
            fontSize      = 11.sp,
            letterSpacing = 1.5.sp,
        )

        Spacer(Modifier.height(40.dp))

        ModelOption(
            title       = "Fake Detector",
            description = "Generates random bounding boxes. Use for UI testing without a model file.",
            selected    = selected == DetectorMode.FAKE,
            enabled     = true,
            onClick     = { selected = DetectorMode.FAKE },
        )

        Spacer(Modifier.height(10.dp))

        ModelOption(
            title       = "TFLite — SSD MobileNet",
            description = if (isTfliteAvailable)
                "On-device object detection. Runs inference off the main thread."
            else
                "Model not found — detect.tflite asset is missing.",
            selected    = selected == DetectorMode.TFLITE,
            enabled     = isTfliteAvailable,
            onClick     = { if (isTfliteAvailable) selected = DetectorMode.TFLITE },
        )

        Spacer(Modifier.height(40.dp))

        Button(
            onClick  = { onConfirm(selected) },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor = FieldAccent,
                contentColor   = Color.Black,
            ),
        ) {
            Text(
                text       = "CONFIRM",
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ModelOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> FieldAccent
        else     -> FieldBorder
    }
    val bgColor = when {
        selected -> Color(0xFF1A1200)
        else     -> FieldSurfaceElevated
    }
    val titleColor = when {
        !enabled -> FieldTextMuted
        selected -> FieldAccent
        else     -> FieldTextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text = description, color = FieldTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (selected) {
            Spacer(Modifier.size(12.dp))
            Spacer(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(FieldAccent),
            )
        }
    }
}
