package com.yotam.droneedge.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurfaceElevated
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    currentMode:       DetectorMode,
    isTfliteAvailable: Boolean,
    onSelect:          (DetectorMode) -> Unit,
    onDismiss:         () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = FieldSurfaceElevated,
        tonalElevation   = 0.dp,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text          = "SELECT MODEL",
                color         = FieldTextMuted,
                fontSize      = 10.sp,
                letterSpacing = 1.5.sp,
                modifier      = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            ModelRow(
                label   = "Fake Detector",
                active  = currentMode == DetectorMode.FAKE,
                enabled = true,
                onClick = { onSelect(DetectorMode.FAKE) },
            )
            ModelRow(
                label   = "TFLite — SSD MobileNet",
                active  = currentMode == DetectorMode.TFLITE,
                enabled = isTfliteAvailable,
                suffix  = if (!isTfliteAvailable) "model not found" else null,
                onClick = { if (isTfliteAvailable) onSelect(DetectorMode.TFLITE) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    label:   String,
    active:  Boolean,
    enabled: Boolean,
    suffix:  String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = label,
            color      = when {
                !enabled -> FieldTextMuted
                active   -> FieldAccent
                else     -> FieldTextPrimary
            },
            fontSize   = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier   = Modifier.weight(1f),
        )
        if (suffix != null) {
            Text(text = suffix, color = FieldTextMuted, fontSize = 11.sp)
        }
        if (active) {
            Text(text = "✓", color = FieldAccent, fontSize = 13.sp)
        }
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FieldBorder),
    )
}
