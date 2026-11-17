package com.voxarium.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF365C45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EADB),
    onPrimaryContainer = Color(0xFF173B26),
    secondary = Color(0xFF546353),
    background = Color(0xFFF7F9F5),
    onBackground = Color(0xFF1A211C),
    surface = Color(0xFFF7F9F5),
    onSurface = Color(0xFF1A211C),
    surfaceVariant = Color(0xFFE3E9DF),
    onSurfaceVariant = Color(0xFF424B42),
    outline = Color(0xFF737D72),
    outlineVariant = Color(0xFFC2CCBF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFABD2B4),
    onPrimary = Color(0xFF143822),
    primaryContainer = Color(0xFF2D5039),
    onPrimaryContainer = Color(0xFFD0EBD5),
    secondary = Color(0xFFBCCCB9),
    background = Color(0xFF111713),
    onBackground = Color(0xFFE0E7DE),
    surface = Color(0xFF111713),
    onSurface = Color(0xFFE0E7DE),
    surfaceVariant = Color(0xFF303B32),
    onSurfaceVariant = Color(0xFFC1CBC0),
    outline = Color(0xFF8B968A),
    outlineVariant = Color(0xFF424D42),
)

/** Explicit theme override also allows deterministic light/dark smoke tests. */
@Composable
fun VoxariumRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
