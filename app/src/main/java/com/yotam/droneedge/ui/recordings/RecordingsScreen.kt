package com.droneedge.app.ui.recordings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.LayoutInflater
import com.droneedge.app.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.droneedge.app.recording.loadDetectionFractions
import com.droneedge.app.ui.theme.FieldAccent
import com.droneedge.app.ui.theme.FieldBackground
import com.droneedge.app.ui.theme.FieldBorder
import com.droneedge.app.ui.theme.FieldSurface
import com.droneedge.app.ui.theme.FieldTextMuted
import com.droneedge.app.ui.theme.FieldTextPrimary
import com.droneedge.app.ui.theme.FieldTextSecondary
import com.droneedge.app.ui.theme.LocalAppStrings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val uri:            Uri,
    val sessionName:    String,
    val durationMs:     Long,
    val dateMs:         Long,
    val thumbnail:      android.graphics.Bitmap?,
    val detectionCount: Int,   // -1 if sidecar not found
)

@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val vm           = viewModel<RecordingsViewModel>()
    val recordings   by vm.recordings.collectAsStateWithLifecycle()
    val error        by vm.error.collectAsStateWithLifecycle()
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }
    val strings      = LocalAppStrings.current

    LaunchedEffect(Unit) { vm.reload() }

    if (playingEntry != null) {
        RecordingPlayer(entry = playingEntry!!, onBack = { playingEntry = null })
    } else {
        RecordingList(
            recordings = recordings,
            onSelect   = { playingEntry = it },
            onRename   = { entry, name -> vm.rename(entry, name) },
            onDelete   = { entry -> vm.delete(entry) },
            onBack     = onBack,
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title            = { Text(strings.error, color = FieldTextPrimary) },
            text             = { Text(msg, color = FieldTextSecondary) },
            confirmButton    = {
                TextButton(onClick = { vm.clearError() }) { Text(strings.ok, color = FieldAccent) }
            },
        )
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun RecordingList(
    recordings: List<RecordingEntry>,
    onSelect:   (RecordingEntry) -> Unit,
    onRename:   (RecordingEntry, String) -> Unit,
    onDelete:   (RecordingEntry) -> Unit,
    onBack:     () -> Unit,
) {
    val strings = LocalAppStrings.current
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
            Text(
                text     = "←",
                color    = FieldAccent,
                fontSize = 32.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text          = strings.galleryTitle,
                color         = FieldTextPrimary,
                fontWeight    = FontWeight.Bold,
                fontSize      = 15.sp,
                letterSpacing = 1.sp,
            )
        }

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.noRecordings, color = FieldTextMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings) { entry ->
                    RecordingRow(
                        entry    = entry,
                        onClick  = { onSelect(entry) },
                        onRename = { name -> onRename(entry, name) },
                        onDelete = { onDelete(entry) },
                    )
                    HorizontalDivider(color = FieldBorder)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    entry:    RecordingEntry,
    onClick:  () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu   by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val strings    = LocalAppStrings.current

    val dateStr = remember(entry.dateMs) {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(entry.dateMs))
    }
    val durationStr = remember(entry.durationMs) {
        val s = entry.durationMs / 1000
        "%d:%02d".format(s / 60, s % 60)
    }

    Box {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                modifier         = Modifier
                    .size(90.dp, 50.dp)
                    .background(FieldBorder),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = entry.thumbnail
                if (bmp != null) {
                    Image(
                        bitmap             = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Text columns
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text       = entry.sessionName,
                        color      = FieldTextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 18.sp,
                        modifier   = Modifier.weight(1f),
                    )
                    Text(text = durationStr, color = FieldTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = dateStr, color = FieldTextSecondary, fontSize = 15.sp)
                    val detStr = if (entry.detectionCount >= 0) "${entry.detectionCount} det." else ""
                    if (detStr.isNotEmpty()) Text(text = detStr, color = FieldTextMuted, fontSize = 15.sp)
                }
            }
        }

        DropdownMenu(
            expanded         = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text    = { Text(strings.rename) },
                onClick = { showMenu = false; showRename = true },
            )
            DropdownMenuItem(
                text    = { Text(strings.delete) },
                onClick = { showMenu = false; showDelete = true },
            )
        }
    }

    if (showRename) {
        var nameText by remember { mutableStateOf(entry.sessionName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title            = { Text(strings.renameSession, color = FieldTextPrimary) },
            text             = {
                OutlinedTextField(
                    value         = nameText,
                    onValueChange = { nameText = it },
                    label         = { Text(strings.sessionNameLabel) },
                    singleLine    = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(nameText.trim()); showRename = false }) {
                    Text(strings.save, color = FieldAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(strings.cancel, color = FieldTextSecondary)
                }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title            = { Text(strings.deleteSession, color = FieldTextPrimary) },
            text             = {
                Text(
                    strings.deleteConfirmBody(entry.sessionName),
                    color = FieldTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) {
                    Text(strings.delete, color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(strings.cancel, color = FieldTextSecondary)
                }
            },
        )
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

    var detectionFractions by remember { mutableStateOf<List<Float>>(emptyList()) }
    var currentPositionMs  by remember { mutableStateOf(0L) }

    LaunchedEffect(entry.sessionName) {
        detectionFractions = withContext(Dispatchers.IO) {
            loadDetectionFractions(context, entry.sessionName, entry.durationMs)
        }
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition
            delay(200L)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory  = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        player        = exoPlayer
                        resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        useController = true
                    }
                },
                update = { it.player = exoPlayer },
            )
            DetectionTimeline(
                fractions   = detectionFractions,
                currentFrac = if (entry.durationMs > 0L)
                    (currentPositionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                else 0f,
                modifier    = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(24.dp),
            )
        }
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x80000000)),
        ) {
            Text("←", color = FieldAccent, fontSize = 32.sp)
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

@Composable
private fun DetectionTimeline(
    fractions:   List<Float>,
    currentFrac: Float,
    modifier:    Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(color = Color(0xCC000000))
        fractions.forEach { f ->
            drawLine(
                color       = Color(0xFFF97316),
                start       = Offset(f * w, h * 0.15f),
                end         = Offset(f * w, h * 0.85f),
                strokeWidth = 2f,
            )
        }
        drawLine(
            color       = Color.White,
            start       = Offset(currentFrac * w, 0f),
            end         = Offset(currentFrac * w, h),
            strokeWidth = 2f,
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
            val id  = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id
            )
            results += RecordingEntry(
                uri            = uri,
                sessionName    = sessionName,
                durationMs     = cursor.getLong(durCol),
                dateMs         = cursor.getLong(dateCol) * 1000L,
                thumbnail      = loadThumbnail(context, uri, id),
                detectionCount = com.droneedge.app.recording.loadDetectionCount(context, sessionName),
            )
        }
    }
    return results
}

private fun loadThumbnail(context: Context, uri: Uri, id: Long): android.graphics.Bitmap? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, android.util.Size(128, 72), null)
        }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver, id,
            MediaStore.Video.Thumbnails.MICRO_KIND, null,
        )
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
                uri            = Uri.fromFile(mp4),
                sessionName    = dir.name,
                durationMs     = 0L,
                dateMs         = dir.lastModified(),
                thumbnail      = null,
                detectionCount = -1,
            )
        }
        ?: emptyList()
}
