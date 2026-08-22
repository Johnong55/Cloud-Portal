package com.trijohn.cloudportal.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2855D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF102C78),
    secondary = Color(0xFF59617A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E5FF),
    onSecondaryContainer = Color(0xFF1B2440),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF171B27),
    surface = Color(0xFFF7F9FF),
    onSurface = Color(0xFF171B27),
    surfaceContainer = Color(0xFFEDF1FA),
    onSurfaceVariant = Color(0xFF5B6070),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF092A70),
    primaryContainer = Color(0xFF173F9A),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFC0C8E8),
    onSecondary = Color(0xFF293149),
    secondaryContainer = Color(0xFF353D57),
    onSecondaryContainer = Color(0xFFE0E5FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE8EAf4),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE8EAF4),
    surfaceContainer = Color(0xFF171D2E),
    onSurfaceVariant = Color(0xFFB7BCCA),
)

@Composable
fun CloudPortalTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
