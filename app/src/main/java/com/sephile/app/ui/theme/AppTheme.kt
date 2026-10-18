package com.sephile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.readOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.View

@Composable
fun SephileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> dynamicDarkColorScheme()
        else -> dynamicLightColorScheme()
    }
    // Enforce the dark Sephile surface palette regardless of system dynamic color
    val sephileColors = darkColorScheme()
    MaterialTheme(
        colorScheme = sephileColors,
        content = content
    )
}

@Composable
private fun darkColorScheme() = androidx.compose.material3.darkColorScheme(
    background = androidx.compose.ui.graphics.Color(0xFF0B0E14),
    surface = androidx.compose.ui.graphics.Color(0xFF121826),
    primary = androidx.compose.ui.graphics.Color(0xFF7CFC00),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0B0E14),
    secondary = androidx.compose.ui.graphics.Color(0xFF3EA700),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    error = androidx.compose.ui.graphics.Color(0xFFFF5555),
    onError = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E2636),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9AA0A6),
    outline = androidx.compose.ui.graphics.Color(0xFF2A3446),
    inverseSurface = androidx.compose.ui.graphics.Color(0xFF121826),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFFE6E6E6),
    inversePrimary = androidx.compose.ui.graphics.Color(0xFF7CFC00)
)

/** Force the Compose view to use a dark surface (sephile look) in previews and edge cases. */
@Composable
fun forceSephileColors() {
    SideEffect {
        val view = LocalView.current
        view.setBackgroundColor(androidx.compose.ui.graphics.Color(0xFF0B0E14).toArgb())
    }
}
