package com.tdeletto.mp3bulk.ui

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

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF),
    onPrimaryContainer = Color(0xFF14104A),
    secondaryContainer = Color(0xFFE4E1F5),
    background = Color(0xFFF8F6FC),
    surface = Color(0xFFF8F6FC),
    surfaceContainerLow = Color(0xFFF2F0F8),
    surfaceContainer = Color(0xFFECEAF3),
    surfaceContainerHigh = Color(0xFFE6E4EE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF231C7A),
    primaryContainer = Color(0xFF3A33B0),
    onPrimaryContainer = Color(0xFFE2DFFF),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
