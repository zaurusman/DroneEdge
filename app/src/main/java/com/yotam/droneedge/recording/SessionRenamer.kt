package com.yotam.droneedge.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun sanitizeSessionName(input: String): String? {
    // Replace characters that MediaStore silently converts in RELATIVE_PATH (':' → '_')
    // so the sidecar directory name always matches what MediaStore stores.
    val trimmed = input.trim().replace('/', '-').replace(':', '_').trim('-')
    return trimmed.ifEmpty { null }
}

internal fun countDetectionLines(file: File): Int {
    if (!file.exists()) return -1
    var count = 0
    file.forEachLine { if (it.isNotBlank() && !it.contains("sessionStart")) count++ }
    return count
}

fun loadDetectionCount(context: Context, sessionName: String): Int {
    val base = context.getExternalFilesDir(null) ?: return -1
    val primary = File(base, "recordings/$sessionName/detections.json")
    if (primary.exists()) return countDetectionLines(primary)
    // Fallback: MediaStore converts ':' → '_' in RELATIVE_PATH but File.renameTo() keeps ':'
    // so older renamed sessions live at the colon path on disk.
    val colonVariant = File(base, "recordings/${sessionName.replace('_', ':')}/detections.json")
    return countDetectionLines(colonVariant)
}

fun loadDetectionFractions(context: Context, sessionName: String, durationMs: Long): List<Float> {
    if (durationMs <= 0L) return emptyList()
    val base = context.getExternalFilesDir(null) ?: return emptyList()
    val primary = File(base, "recordings/$sessionName/detections.json")
    val colonVariant = File(base, "recordings/${sessionName.replace('_', ':')}/detections.json")
    val file = when {
        primary.exists()     -> primary
        colonVariant.exists() -> colonVariant
        else -> return emptyList()
    }
    val tsRegex   = Regex(""""timestampMs":(\d+)""")
    val startRegex = Regex(""""sessionStart":(\d+)""")
    var startMs = -1L
    val fractions = mutableListOf<Float>()
    file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        startRegex.find(line)?.let { startMs = it.groupValues[1].toLong(); return@forEachLine }
        if (startMs < 0L) return@forEachLine
        tsRegex.find(line)?.let { m ->
            val posMs = m.groupValues[1].toLong() - startMs
            fractions += (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }
    return fractions
}

suspend fun renameSession(
    context: Context,
    videoUri: Uri,
    oldName: String,
    newName: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/$newName/")
            }
            context.contentResolver.update(videoUri, cv, null, null)
        }
        val oldDir = File(context.getExternalFilesDir(null), "recordings/$oldName")
        val newDir = File(context.getExternalFilesDir(null), "recordings/$newName")
        if (oldDir.exists()) oldDir.renameTo(newDir)
        true
    } catch (e: Exception) {
        false
    }
}
