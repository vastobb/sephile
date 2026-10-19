package com.sephile.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CFC00),
    onPrimary = Color(0xFF08110A),
    secondary = Color(0xFF50FA7B),
    onSecondary = Color(0xFF08110A),
    tertiary = Color(0xFFFFB86C),
    onTertiary = Color(0xFF150C02),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1B2333),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFFF5555),
    onError = Color(0xFF1A0000),
    outline = Color(0xFF394760),
    outlineVariant = Color(0xFF2A344D)
)

/** Sephile's signature dark "hacker" theme. */
@Composable
fun SephileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
