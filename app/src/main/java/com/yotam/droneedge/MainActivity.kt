package com.yotam.droneedge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.recordings.RecordingsScreen
import com.yotam.droneedge.ui.theme.DroneEdgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroneEdgeTheme {
                var showRecordings by rememberSaveable { mutableStateOf(false) }

                if (showRecordings) {
                    BackHandler { showRecordings = false }
                    RecordingsScreen(onBack = { showRecordings = false })
                } else {
                    LiveScreen(onRecordings = { showRecordings = true })
                }
            }
        }
    }
}
