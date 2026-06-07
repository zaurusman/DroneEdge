package com.droneedge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.droneedge.app.ui.live.DetectorMode
import com.droneedge.app.ui.live.LiveScreen
import com.droneedge.app.ui.live.LiveViewModel
import com.droneedge.app.ui.live.ModelSelectionScreen
import com.droneedge.app.ui.recordings.RecordingsScreen
import com.droneedge.app.ui.theme.AppStrings
import com.droneedge.app.ui.theme.DroneEdgeTheme
import com.droneedge.app.ui.theme.LocalAppStrings
import java.io.File

private const val PREFS_NAME      = "droneedge_prefs"
private const val KEY_DETECTOR    = "detector_mode"
private const val KEY_MODEL_FILE  = "model_file_path"
private const val KEY_LANGUAGE    = "language_code"

class MainActivity : ComponentActivity() {

    private val vm: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensurePublicDirs()
        dumpUsbDevices()
        if (!Environment.isExternalStorageManager()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        intent?.let { vm.handleUsbLaunchIntent(it, this) }
        setContent {
            DroneEdgeTheme {
                var languageCode by rememberSaveable { mutableStateOf(loadLanguageCode()) }
                val appStrings = if (languageCode == "EN") AppStrings.English else AppStrings.Hebrew

                CompositionLocalProvider(LocalAppStrings provides appStrings) {
                    var showModelSelection by rememberSaveable { mutableStateOf(true) }
                    var showGallery        by rememberSaveable { mutableStateOf(false) }

                    when {
                        showModelSelection -> ModelSelectionScreen(
                            initialMode      = loadDetectorMode(),
                            initialFilePath  = loadModelFilePath(),
                            currentLanguage  = languageCode,
                            onConfirm        = { mode, file ->
                                vm.setDetectorMode(mode, this@MainActivity, file)
                                saveDetectorMode(mode)
                                saveModelFilePath(file?.absolutePath)
                                showModelSelection = false
                            },
                            onLanguageChange = { code ->
                                languageCode = code
                                saveLanguage(code)
                            },
                        )
                        showGallery -> {
                            BackHandler { showGallery = false }
                            RecordingsScreen(onBack = { showGallery = false })
                        }
                        else -> LiveScreen(
                            vm        = vm,
                            onGallery = { showGallery = true },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleUsbLaunchIntent(intent, this)
    }

    private fun loadDetectorMode(): DetectorMode {
        val stored = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DETECTOR, DetectorMode.FAKE.name) ?: DetectorMode.FAKE.name
        return runCatching { DetectorMode.valueOf(stored) }.getOrDefault(DetectorMode.FAKE)
    }

    private fun saveDetectorMode(mode: DetectorMode) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DETECTOR, mode.name)
            .apply()
    }

    private fun loadModelFilePath(): String? =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_FILE, null)

    private fun saveModelFilePath(path: String?) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_FILE, path)
            .apply()
    }

    private fun loadLanguageCode(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "HE") ?: "HE"

    private fun saveLanguage(code: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code)
            .apply()
    }

    private fun ensurePublicDirs() {
        droneEdgeModelsDir().mkdirs()
        droneEdgeLogsDir().mkdirs()
    }

    private fun dumpUsbDevices() {
        runCatching {
            val sb = StringBuilder()
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            sb.appendLine("=== DroneEdge diagnostics $ts ===")
            sb.appendLine()

            // ── USB Host devices ──────────────────────────────────────────────
            sb.appendLine("--- USB Host deviceList ---")
            val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
            val devices = usbManager.deviceList
            if (devices.isEmpty()) {
                sb.appendLine("(empty — device may be RNDIS/network or claimed by another app's service)")
            } else {
                devices.values.forEach { dev ->
                    sb.appendLine("Device: ${dev.deviceName}")
                    sb.appendLine("  vendorId  = 0x%04x (%d)".format(dev.vendorId, dev.vendorId))
                    sb.appendLine("  productId = 0x%04x (%d)".format(dev.productId, dev.productId))
                    sb.appendLine("  class     = 0x%02x  subclass = 0x%02x".format(dev.deviceClass, dev.deviceSubclass))
                    sb.appendLine("  interfaces= ${dev.interfaceCount}")
                    for (i in 0 until dev.interfaceCount) {
                        val iface = dev.getInterface(i)
                        sb.appendLine("    iface[$i] class=0x%02x sub=0x%02x proto=0x%02x endpoints=${iface.endpointCount}"
                            .format(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
                    }
                    sb.appendLine("  hasPermission = ${usbManager.hasPermission(dev)}")
                    sb.appendLine()
                }
            }

            // ── Camera2 devices (external cameras appear here) ────────────────
            sb.appendLine()
            sb.appendLine("--- Camera2 camera IDs ---")
            val camManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val camIds = camManager.cameraIdList
            if (camIds.isEmpty()) {
                sb.appendLine("(none)")
            } else {
                camIds.forEach { id ->
                    val chars = runCatching { camManager.getCameraCharacteristics(id) }.getOrNull()
                    val facing = chars?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    val facingStr = when (facing) {
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK  -> "BACK"
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL ← possible goggles"
                        else -> "UNKNOWN($facing)"
                    }
                    sb.appendLine("  Camera id=$id  facing=$facingStr")
                }
            }

            // ── Network interfaces ────────────────────────────────────────────
            sb.appendLine()
            sb.appendLine("--- Network interfaces ---")
            val netIfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            netIfaces.forEach { iface ->
                val addrs = iface.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress }
                    .map { it.hostAddress }
                if (addrs.isNotEmpty()) {
                    sb.appendLine("  ${iface.name}: ${addrs.joinToString()}")
                }
            }

            File(droneEdgeLogsDir(), "diagnostics.txt").writeText(sb.toString())
        }
    }

    companion object {
        fun droneEdgeModelsDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DroneEdge/models")

        fun droneEdgeLogsDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DroneEdge/logs")
    }
}
