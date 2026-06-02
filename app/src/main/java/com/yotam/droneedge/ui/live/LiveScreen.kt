package com.yotam.droneedge.ui.live

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yotam.droneedge.detection.Detection
import com.yotam.droneedge.ui.theme.FieldAccent
import com.yotam.droneedge.ui.theme.FieldBackground
import com.yotam.droneedge.ui.theme.FieldBorder
import com.yotam.droneedge.ui.theme.FieldRecRed
import com.yotam.droneedge.ui.theme.FieldRecRedLight
import com.yotam.droneedge.ui.theme.FieldSurface
import com.yotam.droneedge.ui.theme.FieldTextMuted
import com.yotam.droneedge.ui.theme.FieldTextPrimary
import com.yotam.droneedge.ui.theme.FieldTextSecondary
import com.yotam.droneedge.video.VideoFrame
import com.yotam.droneedge.video.DjiGogglesVideoSource

private const val ACTION_USB_PERMISSION = "com.yotam.droneedge.USB_PERMISSION"
private const val DJI_VENDOR_ID = DjiGogglesVideoSource.VENDOR_ID

@Composable
fun LiveScreen(
    vm:        LiveViewModel = viewModel(),
    onGallery: () -> Unit    = {},
) {
    val sessionState    by vm.sessionState.collectAsStateWithLifecycle()
    val detections      by vm.detections.collectAsStateWithLifecycle()
    val previewFps      by vm.previewFps.collectAsStateWithLifecycle()
    val inferenceFps    by vm.inferenceFps.collectAsStateWithLifecycle()
    val videoUri        by vm.videoUri.collectAsStateWithLifecycle()
    val detectorMode    by vm.detectorMode.collectAsStateWithLifecycle()
    val activeModelFile by vm.activeModelFile.collectAsStateWithLifecycle()
    val error           by vm.error.collectAsStateWithLifecycle()
    val recordingState   by vm.recordingState.collectAsStateWithLifecycle()
    val lastRecording    by vm.lastRecording.collectAsStateWithLifecycle()
    val pendingRename    by vm.pendingRename.collectAsStateWithLifecycle()
    val recordingElapsed by vm.recordingElapsedMs.collectAsStateWithLifecycle()
    val usbDevice        by vm.usbDevice.collectAsStateWithLifecycle()
    val cameraFacing    by vm.cameraFacing.collectAsStateWithLifecycle()
    val djiDevice       by vm.djiDevice.collectAsStateWithLifecycle()

    val context        = LocalContext.current
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)

    var showSourceSheet by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
        else vm.reportError("Camera permission denied — grant it in Settings to use the device camera")
    }

    LaunchedEffect(lifecycleOwner) {
        val facing = cameraFacing
        if (facing != null && sessionState == SessionState.IDLE) {
            vm.useCameraSource(facing, context, lifecycleOwner)
        }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(sessionState) {
        view.keepScreenOn = sessionState == SessionState.RUNNING
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP  -> vm.onAppBackgrounded()
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) vm.useFileSource(uri, context)
    }

    DisposableEffect(Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val permIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                } ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (device.vendorId == DJI_VENDOR_ID) {
                            if (usbManager.hasPermission(device)) vm.useDjiSource(device, ctx)
                            else usbManager.requestPermission(device, permIntent)
                        } else {
                            if (usbManager.hasPermission(device)) vm.useUsbSource(device, ctx)
                            else usbManager.requestPermission(device, permIntent)
                        }
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            if (device.vendorId == DJI_VENDOR_ID) vm.useDjiSource(device, ctx)
                            else vm.useUsbSource(device, ctx)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (device.vendorId == DJI_VENDOR_ID) {
                            vm.reportError("DJI Goggles disconnected")
                            vm.clearDjiSource()
                        } else {
                            vm.reportError("USB camera disconnected")
                            vm.clearUsbSource()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val hudColor = if (sessionState == SessionState.RUNNING) FieldAccent.copy(alpha = 0.75f) else FieldTextMuted.copy(alpha = 0.8f)

    // Derived labels
    val activeSourceChoice: SourceChoice? = when {
        djiDevice    != null -> SourceChoice.DJI
        usbDevice    != null -> SourceChoice.USB
        cameraFacing != null -> SourceChoice.CAMERA
        videoUri     != null -> SourceChoice.FILE
        else                 -> null
    }
    val sourceLabel = when {
        djiDevice    != null -> "DJI"
        usbDevice    != null -> "USB"
        cameraFacing != null -> "Camera"
        videoUri     != null -> "File"
        else                 -> "No Source"
    }
    val modelLabel = activeModelFile?.nameWithoutExtension
        ?: ModelRegistry.all.find { it.mode == detectorMode }?.shortLabel
        ?: detectorMode.name

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────
        when {
            videoUri != null -> VideoPlayer(
                uri       = videoUri!!,
                isPlaying = sessionState == SessionState.RUNNING,
                modifier  = Modifier.fillMaxSize(),
            )
            cameraFacing != null || djiDevice != null -> CameraFrameDisplay(
                frames   = vm.latestFrame,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(modifier = Modifier.fillMaxSize().background(FieldBackground))
        }

        // ── Detection overlay ─────────────────────────────────────────────────
        DetectionOverlay(detections = detections, modifier = Modifier.fillMaxSize())

        // ── HUD top-left: status + source/model when running ──────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text          = "STATUS",
                color         = hudColor,
                fontSize      = 9.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text       = sessionState.name,
                color      = when (sessionState) {
                    SessionState.RUNNING  -> FieldAccent.copy(alpha = 0.85f)
                    SessionState.STOPPING -> Color(0xCCFFAB00)
                    SessionState.IDLE     -> FieldTextMuted.copy(alpha = 0.8f)
                },
                fontWeight = FontWeight.Bold,
                fontSize   = 12.sp,
            )
            Text(
                text     = if (sessionState == SessionState.RUNNING) "$sourceLabel · $modelLabel"
                           else modelLabel,
                color    = hudColor,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── HUD top-right: FPS ────────────────────────────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text          = "FPS",
                color         = hudColor,
                fontSize      = 9.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text  = "PRV ${"%.1f".format(previewFps)}   INF ${"%.1f".format(inferenceFps)}",
                color = hudColor,
                fontSize = 11.sp,
            )
        }

        // ── Error snackbar ────────────────────────────────────────────────────
        if (error != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer,
            ) { Text(error!!) }
        }

        // ── Recording saved snackbar ──────────────────────────────────────────
        if (lastRecording != null) {
            LaunchedEffect(lastRecording) {
                delay(3_000)
                vm.clearLastRecording()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearLastRecording() }) { Text("Dismiss") }
                },
                containerColor = FieldSurface,
                contentColor   = FieldTextSecondary,
            ) { Text("Saved to Movies/DroneEdge/") }
        }

        // ── Bottom bar ────────────────────────────────────────────────────────
        BottomBar(
            modifier       = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            sessionState   = sessionState,
            recordingState = recordingState,
            elapsedMs      = recordingElapsed,
            sourceLabel    = sourceLabel,
            onSourceClick  = { showSourceSheet = true },
            onGallery      = onGallery,
            onStart        = { vm.start() },
            onStop         = { vm.stop() },
            onArmRecording = { vm.armRecording() },
        )

        // ── Sheets ────────────────────────────────────────────────────────────
        if (showSourceSheet) {
            SourceSheet(
                activeChoice = activeSourceChoice,
                onDismiss    = { showSourceSheet = false },
                onSelect     = { choice ->
                    showSourceSheet = false
                    when (choice) {
                        SourceChoice.CAMERA -> {
                            val permission = Manifest.permission.CAMERA
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
                            } else {
                                cameraPermissionLauncher.launch(permission)
                            }
                        }
                        SourceChoice.USB -> {
                            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                            val uvcDevice  = usbManager.deviceList.values.firstOrNull { dev ->
                                (0 until dev.interfaceCount).any { i ->
                                    val iface = dev.getInterface(i)
                                    iface.interfaceClass == 0x0E && iface.interfaceSubclass == 0x02
                                }
                            }
                            if (uvcDevice == null) {
                                vm.reportError("No UVC camera found — connect a USB camera and try again")
                            } else if (usbManager.hasPermission(uvcDevice)) {
                                vm.useUsbSource(uvcDevice, context)
                            } else {
                                usbManager.requestPermission(
                                    uvcDevice,
                                    PendingIntent.getBroadcast(
                                        context, 0,
                                        Intent(ACTION_USB_PERMISSION),
                                        PendingIntent.FLAG_IMMUTABLE,
                                    ),
                                )
                            }
                        }
                        SourceChoice.DJI -> {
                            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                            val djiDev = usbManager.deviceList.values.firstOrNull { dev ->
                                dev.vendorId == DJI_VENDOR_ID
                            }
                            when {
                                djiDev == null ->
                                    vm.reportError("No DJI Goggles detected — connect via USB and power on the drone")
                                usbManager.hasPermission(djiDev) ->
                                    vm.useDjiSource(djiDev, context)
                                else ->
                                    usbManager.requestPermission(
                                        djiDev,
                                        PendingIntent.getBroadcast(
                                            context, 0,
                                            Intent(ACTION_USB_PERMISSION),
                                            PendingIntent.FLAG_IMMUTABLE,
                                        ),
                                    )
                            }
                        }
                        SourceChoice.FILE -> filePicker.launch("video/*")
                        SourceChoice.FAKE -> vm.useFakeSource()
                    }
                },
            )
        }

        // ── Post-recording naming dialog ──────────────────────────────────────
        pendingRename?.let { result ->
            NamingDialog(
                sessionId = result.sessionId,
                onConfirm = { name -> vm.finalizeSessionName(result, name) },
                onSkip    = { vm.skipNaming(result) },
            )
        }
    }
}

// ── Bottom bar ────────────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    modifier:       Modifier,
    sessionState:   SessionState,
    recordingState: RecordingState,
    elapsedMs:      Long,
    sourceLabel:    String,
    onSourceClick:  () -> Unit,
    onGallery:      () -> Unit,
    onStart:        () -> Unit,
    onStop:         () -> Unit,
    onArmRecording: () -> Unit,
) {
    Row(
        modifier              = modifier
            .background(Color(0xEE111111))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sessionState == SessionState.IDLE) {
            OutlinedButton(
                onClick = onSourceClick,
                colors  = ButtonDefaults.outlinedButtonColors(
                    contentColor = FieldAccent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldAccent),
            ) { Text("$sourceLabel ▾", fontSize = 12.sp) }

            OutlinedButton(
                onClick = onGallery,
                colors  = ButtonDefaults.outlinedButtonColors(
                    contentColor = FieldAccent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldAccent),
            ) { Text("Gallery", fontSize = 12.sp) }
        }

        if (sessionState == SessionState.RUNNING) {
            RecButton(recordingState = recordingState, elapsedMs = elapsedMs, onArmRecording = onArmRecording)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick  = { if (sessionState == SessionState.IDLE) onStart() else onStop() },
            enabled  = sessionState != SessionState.STOPPING,
            colors   = ButtonDefaults.buttonColors(
                containerColor = FieldAccent,
                contentColor   = Color.Black,
            ),
        ) {
            Text(
                text       = when (sessionState) {
                    SessionState.IDLE     -> "START"
                    SessionState.RUNNING  -> "STOP"
                    SessionState.STOPPING -> "STOPPING"
                },
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontSize      = 12.sp,
            )
        }
    }
}

// ── Breathing REC button ──────────────────────────────────────────────────────

@Composable
private fun RecButton(
    recordingState: RecordingState,
    elapsedMs:      Long,
    onArmRecording: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recDotAlpha",
    )

    when (recordingState) {
        RecordingState.IDLE -> OutlinedButton(
            onClick = onArmRecording,
            colors  = ButtonDefaults.outlinedButtonColors(contentColor = FieldRecRedLight),
            border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
        ) { Text("REC", fontSize = 12.sp) }

        RecordingState.ARMED -> {
            val timerStr = remember(elapsedMs) {
                "%d:%02d".format(elapsedMs / 60_000L, (elapsedMs / 1000L) % 60L)
            }
            OutlinedButton(
                onClick = {},
                colors  = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x331A0000),
                    contentColor   = FieldRecRedLight,
                ),
                border  = androidx.compose.foundation.BorderStroke(1.dp, FieldRecRed),
            ) {
                Text(
                    text       = "● REC  $timerStr",
                    fontSize   = 12.sp,
                    color      = FieldRecRedLight.copy(alpha = dotAlpha),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        RecordingState.FINALIZING -> OutlinedButton(
            onClick  = {},
            enabled  = false,
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = FieldTextMuted),
            border   = androidx.compose.foundation.BorderStroke(1.dp, FieldBorder),
        ) { Text("Saving…", fontSize = 12.sp) }
    }
}

// ── Video player ──────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayer(uri: Uri, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode    = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }
    LaunchedEffect(isPlaying) { exoPlayer.playWhenReady = isPlaying }
    DisposableEffect(uri) { onDispose { exoPlayer.release() } }
    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            PlayerView(ctx).apply {
                player        = exoPlayer
                useController = false
                resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { it.player = exoPlayer },
    )
}

// ── Detection overlay ─────────────────────────────────────────────────────────

private val boxColor     = FieldAccent
private val labelBgColor = Color(0xCC000000)
private val labelStyle   = TextStyle(fontSize = 11.sp, color = Color.White)

@Composable
private fun DetectionOverlay(detections: List<Detection>, modifier: Modifier = Modifier) {
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
            drawRect(color = boxColor, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(3f))
            val label    = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val measured = textMeasurer.measure(label, style = labelStyle)
            val stripH   = measured.size.height.toFloat()
            drawRect(
                color   = labelBgColor,
                topLeft = Offset(l, t - stripH),
                size    = Size(maxOf(r - l, measured.size.width.toFloat()), stripH),
            )
            drawText(textLayoutResult = measured, topLeft = Offset(l + 4f, t - stripH))
        }
    }
}

// ── Camera frame display ──────────────────────────────────────────────────────

@Composable
private fun CameraFrameDisplay(
    frames:   kotlinx.coroutines.flow.StateFlow<VideoFrame?>,
    modifier: Modifier = Modifier,
) {
    val frame by frames.collectAsStateWithLifecycle()
    val bmp   = frame?.bitmap
    if (bmp != null && !bmp.isRecycled) {
        Canvas(modifier = modifier) {
            val scale = maxOf(size.width / bmp.width, size.height / bmp.height)
            val srcW  = (size.width  / scale).toInt().coerceAtMost(bmp.width)
            val srcH  = (size.height / scale).toInt().coerceAtMost(bmp.height)
            val srcX  = (bmp.width  - srcW) / 2
            val srcY  = (bmp.height - srcH) / 2
            drawImage(
                image     = bmp.asImageBitmap(),
                srcOffset = IntOffset(srcX, srcY),
                srcSize   = IntSize(srcW, srcH),
                dstOffset = IntOffset.Zero,
                dstSize   = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    } else {
        Box(modifier = modifier.background(FieldBackground))
    }
}

// ── Post-recording naming dialog ──────────────────────────────────────────────

@Composable
private fun NamingDialog(
    sessionId: String,
    onConfirm: (String) -> Unit,
    onSkip:    () -> Unit,
) {
    val defaultName = remember(sessionId) {
        runCatching {
            val ts     = sessionId.removePrefix("session_")
            val parsed = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(ts)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(parsed!!)
        }.getOrDefault(sessionId)
    }
    var name by remember(sessionId) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Name this recording", color = FieldTextPrimary) },
        text  = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Session name") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = FieldAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Skip", color = FieldTextMuted)
            }
        },
        containerColor = FieldSurface,
    )
}
