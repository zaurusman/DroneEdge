package com.yotam.droneedge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.yotam.droneedge.ui.live.DetectorMode
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.live.LiveViewModel
import com.yotam.droneedge.ui.live.ModelSelectionScreen
import com.yotam.droneedge.ui.recordings.RecordingsScreen
import com.yotam.droneedge.ui.theme.DroneEdgeTheme

private const val PREFS_NAME      = "droneedge_prefs"
private const val KEY_DETECTOR    = "detector_mode"
private const val KEY_MODEL_FILE  = "model_file_path"

class MainActivity : ComponentActivity() {

    private val vm: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.let { vm.handleUsbLaunchIntent(it, this) }
        setContent {
            DroneEdgeTheme {
                var showModelSelection by rememberSaveable { mutableStateOf(true) }
                var showGallery        by rememberSaveable { mutableStateOf(false) }

                when {
                    showModelSelection -> ModelSelectionScreen(
                        initialMode     = loadDetectorMode(),
                        initialFilePath = loadModelFilePath(),
                        onConfirm       = { mode, file ->
                            vm.setDetectorMode(mode, this@MainActivity, file)
                            saveDetectorMode(mode)
                            saveModelFilePath(file?.absolutePath)
                            showModelSelection = false
                        },
                    )
                    showGallery -> {
                        BackHandler { showGallery = false }
                        RecordingsScreen(onBack = { showGallery = false })
                    }
                    else -> LiveScreen(
                        vm           = vm,
                        onGallery    = { showGallery = true },
                    )
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

}
