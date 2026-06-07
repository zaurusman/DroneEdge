package com.droneedge.app.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneedge.app.ui.theme.FieldAccent
import com.droneedge.app.ui.theme.FieldBackground
import com.droneedge.app.ui.theme.FieldBorder
import com.droneedge.app.ui.theme.FieldSurfaceElevated
import com.droneedge.app.ui.theme.FieldTextMuted
import com.droneedge.app.ui.theme.FieldTextPrimary
import com.droneedge.app.ui.theme.FieldTextSecondary
import com.droneedge.app.ui.theme.LocalAppStrings
import java.io.File

@Composable
fun ModelSelectionScreen(
    initialMode:      DetectorMode,
    initialFilePath:  String? = null,
    currentLanguage:  String = "HE",
    onConfirm:        (DetectorMode, File?) -> Unit,
    onLanguageChange: (String) -> Unit = {},
) {
    val strings      = LocalAppStrings.current
    val context      = LocalContext.current
    val availability = remember {
        ModelRegistry.all.associate { it.mode to it.isAvailable(context.assets) }
    }
    val externalModels: List<File> = remember {
        val dir = context.getExternalFilesDir("models")
        dir?.listFiles { f -> f.extension == "tflite" }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    var selectedMode     by rememberSaveable { mutableStateOf(initialMode) }
    var selectedFilePath by rememberSaveable { mutableStateOf(initialFilePath) }

    Box(modifier = Modifier.fillMaxSize().background(FieldBackground)) {

        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                text          = "DRONEEDGE",
                color         = FieldAccent,
                fontSize      = 26.sp,
                fontWeight    = FontWeight.ExtraBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text          = strings.selectModel,
                color         = FieldTextMuted,
                fontSize      = 11.sp,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(40.dp))

            // ── Built-in models ───────────────────────────────────────────────────
            ModelRegistry.all.forEachIndexed { index, descriptor ->
                val available = availability[descriptor.mode] ?: false
                ModelOption(
                    title       = descriptor.displayName,
                    description = if (available) descriptor.description
                                  else strings.modelNotFound(descriptor.assetFile ?: ""),
                    selected    = selectedMode == descriptor.mode && selectedFilePath == null,
                    enabled     = available,
                    onClick     = {
                        if (available) {
                            selectedMode     = descriptor.mode
                            selectedFilePath = null
                        }
                    },
                )
                if (index < ModelRegistry.all.lastIndex || externalModels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── External models ───────────────────────────────────────────────────
            externalModels.forEachIndexed { index, file ->
                ModelOption(
                    title       = file.nameWithoutExtension,
                    description = strings.externalModelDesc,
                    selected    = selectedFilePath == file.absolutePath,
                    enabled     = true,
                    onClick     = {
                        selectedMode     = DetectorMode.TFLITE
                        selectedFilePath = file.absolutePath
                    },
                )
                if (index < externalModels.lastIndex) Spacer(Modifier.height(10.dp))
            }

            if (externalModels.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text       = strings.externalModelHint,
                    color      = FieldTextMuted,
                    fontSize   = 10.sp,
                    lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick  = {
                    val file = selectedFilePath?.let { File(it) }
                    onConfirm(selectedMode, file)
                },
                modifier = Modifier.widthIn(min = 160.dp, max = 240.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = FieldAccent,
                    contentColor   = Color.Black,
                ),
            ) {
                Text(
                    text          = strings.confirm,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(Modifier.weight(1f))
        }

        // ── Language toggle ───────────────────────────────────────────────────────
        LanguageToggle(
            currentLanguage  = currentLanguage,
            onLanguageChange = onLanguageChange,
            modifier         = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun LanguageToggle(
    currentLanguage:  String,
    onLanguageChange: (String) -> Unit,
    modifier:         Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("EN", "HE").forEach { code ->
            val active = currentLanguage == code
            Text(
                text       = code,
                color      = if (active) FieldAccent else FieldTextMuted,
                fontSize   = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier   = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = 1.dp,
                        color = if (active) FieldAccent else FieldBorder,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable(enabled = !active) { onLanguageChange(code) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelOption(
    title:       String,
    description: String,
    selected:    Boolean,
    enabled:     Boolean,
    onClick:     () -> Unit,
) {
    val borderColor = if (selected) FieldAccent else FieldBorder
    val bgColor     = if (selected) Color(0xFF1A1200) else FieldSurfaceElevated
    val titleColor  = when {
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
