package com.yotam.droneedge.ui.recordings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldSurface
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val uri:         Uri,
    val sessionName: String,
    val durationMs:  Long,
    val dateMs:      Long,
)

@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val vm         = viewModel<RecordingsViewModel>()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    if (playingEntry != null) {
        RecordingPlayer(entry = playingEntry!!, onBack = { playingEntry = null })
    } else {
        RecordingList(recordings = recordings, onSelect = { playingEntry = it }, onBack = onBack)
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun RecordingList(
    recordings: List<RecordingEntry>,
    onSelect:   (RecordingEntry) -> Unit,
    onBack:     () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FieldBackground)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .background(FieldSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("←", color = FieldAccent, fontSize = 20.sp)
            }
            Text(
                text          = "GALLERY",
                color         = FieldTextPrimary,
                fontWeight    = FontWeight.Bold,
                fontSize      = 15.sp,
                letterSpacing = 1.sp,
            )
        }

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings found.", color = FieldTextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings) { entry ->
                    RecordingRow(entry = entry, onClick = { onSelect(entry) })
                    HorizontalDivider(color = FieldBorder)
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(entry: RecordingEntry, onClick: () -> Unit) {
    val dateStr = remember(entry.dateMs) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(entry.dateMs))
    }
    val durationStr = remember(entry.durationMs) {
        val s = entry.durationMs / 1000
        "%d:%02d".format(s / 60, s % 60)
    }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = entry.sessionName, color = FieldTextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(text = dateStr, color = FieldTextSecondary, fontSize = 11.sp)
        }
        Text(text = durationStr, color = FieldTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Full-screen player ────────────────────────────────────────────────────────

@Composable
private fun RecordingPlayer(entry: RecordingEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember(entry.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(entry.uri))
            repeatMode    = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(entry.uri) { onDispose { exoPlayer.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    player        = exoPlayer
                    resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = true
                }
            },
            update = { it.player = exoPlayer },
        )
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x80000000)),
        ) {
            Text("←", color = FieldAccent, fontSize = 20.sp)
        }
        Text(
            text     = entry.sessionName,
            color    = FieldTextSecondary.copy(alpha = 0.8f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color(0x80000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── MediaStore query ──────────────────────────────────────────────────────────

internal fun queryRecordings(context: Context): List<RecordingEntry> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryRecordingsMediaStore(context)
    else queryRecordingsFileSystem()

internal fun queryRecordingsMediaStore(context: Context): List<RecordingEntry> {
    val results    = mutableListOf<RecordingEntry>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.RELATIVE_PATH,
    )
    context.contentResolver.query(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("Movies/DroneEdge/%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val relativePath = cursor.getString(pathCol) ?: ""
            val sessionName  = relativePath.trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotEmpty() } ?: cursor.getString(nameCol)
            results += RecordingEntry(
                uri         = ContentUris.withAppendedId(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    cursor.getLong(idCol),
                ),
                sessionName = sessionName,
                durationMs  = cursor.getLong(durCol),
                dateMs      = cursor.getLong(dateCol) * 1000L,
            )
        }
    }
    return results
}

internal fun queryRecordingsFileSystem(): List<RecordingEntry> {
    val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DroneEdge"
    )
    if (!root.exists()) return emptyList()
    return root.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { dir ->
            val mp4 = File(dir, "annotated.mp4")
            if (!mp4.exists()) return@mapNotNull null
            RecordingEntry(
                uri         = Uri.fromFile(mp4),
                sessionName = dir.name,
                durationMs  = 0L,
                dateMs      = dir.lastModified(),
            )
        }
        ?: emptyList()
}
