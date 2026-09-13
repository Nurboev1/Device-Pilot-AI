package com.devicepilot.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkSlateColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkSlateBackground,
    primaryContainer = DarkSlateSurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = AccentIndigo,
    onSecondary = DarkSlateBackground,
    background = DarkSlateBackground,
    onBackground = TextPrimary,
    surface = DarkSlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = StatusRose,
    onError = TextPrimary
)

@Composable
fun DevicePilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkSlateColorScheme,
        content = content
    )
}
