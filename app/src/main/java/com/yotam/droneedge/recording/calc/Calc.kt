package com.droneedge.app.recording.calc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.droneedge.app.ui.recordings.RecordingEntry
import java.io.File

/**
 * Reads the raw recording + its detections.json, runs [VideoAnnotator], and writes
 * `annotated_boxed.mp4` into the same MediaStore session folder.
 */
fun runCalc(context: Context, entry: RecordingEntry, onProgress: (Float) -> Unit) {
    val jsonFile = File(
        context.getExternalFilesDir(null),
        "recordings/${entry.sessionName}/detections.json",
    )
    require(jsonFile.exists()) { "no detections.json for ${entry.sessionName}" }
    val track = DetectionTrack.parse(jsonFile.readLines())

    val inputFd = context.contentResolver.openFileDescriptor(entry.uri, "r")
        ?: error("cannot open ${entry.uri}")

    val outUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "annotated_boxed.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/${entry.sessionName}/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        context.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
        ) ?: error("MediaStore insert failed")
    } else {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES
            ),
            "DroneEdge/${entry.sessionName}"
        ).also { it.mkdirs() }
        android.net.Uri.fromFile(File(dir, "annotated_boxed.mp4"))
    }
    val outputFd = context.contentResolver.openFileDescriptor(outUri, "rw")
        ?: error("cannot open output")

    try {
        VideoAnnotator(inputFd, outputFd, track, onProgress).run()
    } finally {
        runCatching { inputFd.close() }
        runCatching { outputFd.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                outUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }
}
