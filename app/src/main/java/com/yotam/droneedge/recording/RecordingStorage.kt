package com.droneedge.app.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/** Shared storage for session recorders: the MediaStore video file + the JSON sidecar. */
object RecordingStorage {

    data class VideoFile(val pfd: ParcelFileDescriptor, val uri: Uri)
    data class JsonFile(val writer: BufferedWriter, val uri: Uri)

    /** Opens `Movies/DroneEdge/<sessionName>/annotated.mp4` for writing (pending on API 29+). */
    fun openVideoFile(context: Context, sessionName: String): VideoFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "annotated.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneEdge/$sessionName/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
            ) ?: error("MediaStore insert failed for video")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Cannot open file descriptor for $uri")
            VideoFile(pfd, uri)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "DroneEdge/$sessionName"
            ).also { it.mkdirs() }
            val file = File(dir, "annotated.mp4")
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
            VideoFile(pfd, Uri.fromFile(file))
        }
    }

    /** Opens `<externalFilesDir>/recordings/<sessionName>/detections.json` (adb-accessible). */
    fun openJsonWriter(context: Context, sessionName: String): JsonFile {
        val dir = File(context.getExternalFilesDir(null), "recordings/$sessionName").also { it.mkdirs() }
        val file = File(dir, "detections.json")
        return JsonFile(BufferedWriter(FileWriter(file)), Uri.fromFile(file))
    }

    /** Clears IS_PENDING so the video becomes visible (API 29+). No-op below Q. */
    fun finalizeVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
            )
        }
    }
}
