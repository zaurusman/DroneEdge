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
    val trimmed = input.trim().replace('/', '-').trim('-')
    return trimmed.ifEmpty { null }
}

internal fun countDetectionLines(file: File): Int {
    if (!file.exists()) return -1
    var count = 0
    file.forEachLine { if (it.isNotBlank()) count++ }
    return count
}

fun loadDetectionCount(context: Context, sessionName: String): Int =
    countDetectionLines(
        File(context.getExternalFilesDir(null), "recordings/$sessionName/detections.json")
    )

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
