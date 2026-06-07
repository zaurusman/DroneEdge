package com.yotam.droneedge

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.yotam.droneedge.ui.live.DetectorMode
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.live.LiveViewModel
import com.yotam.droneedge.ui.live.ModelSelectionScreen
import com.yotam.droneedge.ui.recordings.RecordingsScreen
import com.yotam.droneedge.ui.theme.AppStrings
import com.yotam.droneedge.ui.theme.DroneEdgeTheme
import com.yotam.droneedge.ui.theme.LocalAppStrings

private const val PREFS_NAME      = "droneedge_prefs"
private const val KEY_DETECTOR    = "detector_mode"
private const val KEY_MODEL_FILE  = "model_file_path"
private const val KEY_LANGUAGE    = "language_code"

class MainActivity : ComponentActivity() {

    private val vm: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
