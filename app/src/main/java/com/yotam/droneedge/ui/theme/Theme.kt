package com.droneedge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DroneEdgeColorScheme = darkColorScheme(
    primary          = FieldAccent,
    onPrimary        = Color.Black,
    background       = FieldBackground,
    onBackground     = FieldTextPrimary,
    surface          = FieldSurface,
    onSurface        = FieldTextPrimary,
    error            = FieldRecRed,
    onError          = Color.White,
    surfaceVariant   = FieldSurfaceElevated,
    onSurfaceVariant = FieldTextSecondary,
    outline          = FieldBorder,
)

@Composable
fun DroneEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroneEdgeColorScheme,
        typography  = Typography,
        content     = content,
    )
}