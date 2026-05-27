package com.yotam.droneedge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yotam.droneedge.ui.live.LiveScreen
import com.yotam.droneedge.ui.theme.DroneEdgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroneEdgeTheme {
                LiveScreen()
            }
        }
    }
}
