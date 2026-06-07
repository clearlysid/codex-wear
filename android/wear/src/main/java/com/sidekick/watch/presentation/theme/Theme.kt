package com.sidekick.watch.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val neutralColorScheme = ColorScheme(
    primary = Color(0xFFE6E6E6),
    primaryDim = Color(0xFFB8B8B8),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimary = Color(0xFF121212),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFD0D0D0),
    secondaryDim = Color(0xFFA8A8A8),
    secondaryContainer = Color(0xFF303030),
    onSecondary = Color(0xFF161616),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFC4C4C4),
    tertiaryDim = Color(0xFF969696),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiary = Color(0xFF181818),
    onTertiaryContainer = Color(0xFFE8E8E8),
    surfaceContainer = Color(0xFF202020),
    onSurface = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFFB8B8B8),
    error = Color(0xFFD6D6D6),
    onError = Color(0xFF151515),
)

@Composable
fun SidekickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = neutralColorScheme,
        content = content,
    )
}
