package com.alw.workstation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1793D1), // Arch Cyan
    onPrimary = Color.White,
    primaryContainer = Color(0xFF004D73),
    onPrimaryContainer = Color(0xFFBCE9FF),
    secondary = Color(0xFF00E676), // Neon Green accent
    onSecondary = Color.Black,
    background = Color(0xFF121417),
    surface = Color(0xFF1B1E23),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFA0A5B5),
    error = Color(0xFFFF5252)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1793D1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EFFF),
    onPrimaryContainer = Color(0xFF003550),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    onSurface = Color(0xFF1E2024),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF5A6070),
    error = Color(0xFFD32F2F)
)

@Composable
fun WorkstationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme // Workstation defaults to dark theme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
