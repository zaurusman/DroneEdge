package com.yotam.droneedge

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
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.live.LiveViewModel
import com.yotam.droneedge.ui.recordings.RecordingsScreen
import com.yotam.droneedge.ui.theme.DroneEdgeTheme

class MainActivity : ComponentActivity() {

    private val vm: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.let { vm.handleUsbLaunchIntent(it, this) }
        setContent {
            DroneEdgeTheme {
                var showRecordings by rememberSaveable { mutableStateOf(false) }

                if (showRecordings) {
                    BackHandler { showRecordings = false }
                    RecordingsScreen(onBack = { showRecordings = false })
                } else {
                    LiveScreen(vm = vm, onRecordings = { showRecordings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleUsbLaunchIntent(intent, this)
    }
}
