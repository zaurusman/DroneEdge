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
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yotam.droneedge.detection.BoundingBox
import com.yotam.droneedge.detection.Detection

private const val ACTION_USB_PERMISSION = "com.yotam.droneedge.USB_PERMISSION"

@Composable
fun LiveScreen(
    vm: LiveViewModel = viewModel(),
    onRecordings: () -> Unit = {},
) {
    val sessionState   by vm.sessionState.collectAsStateWithLifecycle()
    val detections     by vm.detections.collectAsStateWithLifecycle()
    val previewFps     by vm.previewFps.collectAsStateWithLifecycle()
    val inferenceFps   by vm.inferenceFps.collectAsStateWithLifecycle()
    val videoUri       by vm.videoUri.collectAsStateWithLifecycle()
    val detectorMode   by vm.detectorMode.collectAsStateWithLifecycle()
    val error          by vm.error.collectAsStateWithLifecycle()
    val recordingState by vm.recordingState.collectAsStateWithLifecycle()
    val lastRecording  by vm.lastRecording.collectAsStateWithLifecycle()
    val usbDevice      by vm.usbDevice.collectAsStateWithLifecycle()
    val cameraFacing    by vm.cameraFacing.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
    }

    // Re-create CameraVideoSource after configuration changes (rotation) so the stored
    // LifecycleOwner is always current. Only re-creates when camera is already selected and IDLE.
    LaunchedEffect(lifecycleOwner) {
        val facing = vm.cameraFacing.value
        if (facing != null && vm.sessionState.value == SessionState.IDLE) {
            vm.useCameraSource(facing, context, lifecycleOwner)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) vm.useFileSource(uri, context)
    }

    // Register for USB attach/detach/permission broadcasts for as long as the screen is visible.
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
                        if (usbManager.hasPermission(device)) vm.useUsbSource(device, ctx)
                        else usbManager.requestPermission(device, permIntent)
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) vm.useUsbSource(device, ctx)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        vm.reportError("USB camera disconnected")
                        vm.clearUsbSource()
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

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background: video or dark canvas ─────────────────────────────────
        if (videoUri != null) {
            VideoPlayer(
                uri       = videoUri!!,
                isPlaying = sessionState == SessionState.RUNNING,
                modifier  = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1117))
            )
        }

        // ── Detection overlay (always on top of background) ───────────────────
        DetectionOverlay(
            detections = detections,
            modifier   = Modifier.fillMaxSize(),
        )

        // ── FPS HUD (top-right) ───────────────────────────────────────────────
        Column(
            modifier              = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalAlignment   = Alignment.End,
        ) {
            HudText("Preview   ${"%.1f".format(previewFps)} fps")
            HudText("Inference ${"%.1f".format(inferenceFps)} fps")
        }

        // ── Session + source badges (top-left) ────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text       = sessionState.name,
                color      = when (sessionState) {
                    SessionState.RUNNING  -> Color(0xFF00E676)
                    SessionState.STOPPING -> Color(0xFFFFAB00)
                    SessionState.IDLE     -> Color(0xFF757575)
                },
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
            )
            when {
                usbDevice != null -> Text(
                    text     = "USB: ${usbDevice!!.productName ?: usbDevice!!.deviceName}",
                    color    = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(Color(0x80000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                cameraFacing != null -> HudText(
                    if (cameraFacing == CameraSelector.LENS_FACING_BACK) "CAM: back" else "CAM: front"
                )
                videoUri != null -> Text(
                    text     = "FILE: ${videoUri!!.lastPathSegment ?: "video"}",
                    color    = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(Color(0x80000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                else -> HudText("FAKE SOURCE")
            }
        }

        // ── Error snackbar (bottom, above controls) ───────────────────────────
        if (error != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(error!!)
            }
        }

        // ── Recording saved snackbar ──────────────────────────────────────────
        if (lastRecording != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                action = {
                    TextButton(onClick = { vm.clearLastRecording() }) { Text("Dismiss") }
                },
            ) {
                Text("Saved to Movies/DroneEdge/")
            }
        }

        // ── Bottom controls ───────────────────────────────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Detector toggle row (only while idle)
            if (sessionState == SessionState.IDLE) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "Detector:",
                        color    = Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = { vm.setDetectorMode(DetectorMode.FAKE) },
                        colors  = if (detectorMode == DetectorMode.FAKE)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        else ButtonDefaults.outlinedButtonColors(),
                    ) { Text("Fake") }
                    OutlinedButton(
                        onClick = { vm.setDetectorMode(DetectorMode.TFLITE, context) },
                        colors  = if (detectorMode == DetectorMode.TFLITE)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        else ButtonDefaults.outlinedButtonColors(),
                    ) { Text("TFLite") }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Recordings browser (only while idle)
                if (sessionState == SessionState.IDLE) {
                    OutlinedButton(onClick = onRecordings) { Text("Recordings") }
                }

                // File picker / clear button (only while idle)
                if (sessionState == SessionState.IDLE) {
                    if (videoUri == null) {
                        OutlinedButton(onClick = { filePicker.launch("video/*") }) {
                            Text("Pick Video")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.useFakeSource() },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Clear Video")
                        }
                    }
                }

                // USB camera connect / clear button (only while idle)
                if (sessionState == SessionState.IDLE) {
                    if (usbDevice == null) {
                        OutlinedButton(onClick = {
                            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                            val uvcDevice = usbManager.deviceList.values.firstOrNull { dev ->
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
                        }) {
                            Text("USB Cam")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.clearUsbSource() },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Clear USB")
                        }
                    }
                }

                // Camera connect / clear button (only while idle)
                if (sessionState == SessionState.IDLE) {
                    if (cameraFacing == null) {
                        OutlinedButton(onClick = {
                            val permission = Manifest.permission.CAMERA
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                vm.useCameraSource(CameraSelector.LENS_FACING_BACK, context, lifecycleOwner)
                            } else {
                                cameraPermissionLauncher.launch(permission)
                            }
                        }) {
                            Text("Camera")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.clearCameraSource() },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Clear Cam")
                        }
                    }
                }

                // Recording indicator (only while running)
                if (sessionState == SessionState.RUNNING) {
                    when (recordingState) {
                        // Shouldn't normally appear (auto-armed on start) but allows re-arm if
                        // the user manually stopped recording mid-session.
                        RecordingState.IDLE -> OutlinedButton(
                            onClick = { vm.armRecording() },
                        ) { Text("REC") }

                        RecordingState.ARMED -> Button(
                            onClick  = { vm.disarmRecording() },
                            enabled  = false,
                            colors   = ButtonDefaults.buttonColors(
                                disabledContainerColor = Color(0xFFD32F2F),
                                disabledContentColor   = Color.White,
                            ),
                        ) { Text("● REC") }

                        RecordingState.FINALIZING -> OutlinedButton(
                            onClick  = {},
                            enabled  = false,
                        ) { Text("Saving…") }
                    }
                }

                // Start / Stop
                Button(
                    onClick = {
                        when (sessionState) {
                            SessionState.IDLE     -> vm.start()
                            SessionState.RUNNING  -> vm.stop()
                            SessionState.STOPPING -> Unit
                        }
                    },
                    enabled = sessionState != SessionState.STOPPING,
                ) {
                    Text(
                        text = when (sessionState) {
                            SessionState.IDLE     -> "Start"
                            SessionState.RUNNING  -> "Stop"
                            SessionState.STOPPING -> "Stopping…"
                        }
                    )
                }
            }
        }
    }
}

// ── Video player ──────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayer(
    uri: Uri,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }

    // Start/stop driven by session state
    LaunchedEffect(isPlaying) {
        exoPlayer.playWhenReady = isPlaying
    }

    // Release when leaving composition or when URI changes
    DisposableEffect(uri) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            PlayerView(ctx).apply {
                player         = exoPlayer
                useController  = false                             // our own controls
                resizeMode     = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // fill, keep AR
            }
        },
        update = { playerView -> playerView.player = exoPlayer },
    )
}

// ── Detection overlay ─────────────────────────────────────────────────────────

private val boxColor     = Color(0xFF00E5FF)
private val labelBgColor = Color(0xCC000000)
private val labelStyle   = TextStyle(fontSize = 11.sp, color = Color.White)

@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier,
) {
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

            drawRect(
                color   = boxColor,
                topLeft = Offset(l, t),
                size    = Size(r - l, b - t),
                style   = Stroke(width = 3f),
            )

            val labelText = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val measured  = textMeasurer.measure(labelText, style = labelStyle)
            val stripH    = measured.size.height.toFloat()

            drawRect(
                color   = labelBgColor,
                topLeft = Offset(l, t - stripH),
                size    = Size(maxOf(r - l, measured.size.width.toFloat()), stripH),
            )

            drawText(
                textLayoutResult = measured,
                topLeft          = Offset(l + 4f, t - stripH),
            )
        }
    }
}

// ── HUD text ──────────────────────────────────────────────────────────────────

@Composable
private fun HudText(text: String) {
    Text(
        text       = text,
        color      = Color(0xFFE0E0E0),
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier   = Modifier
            .background(Color(0x80000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
