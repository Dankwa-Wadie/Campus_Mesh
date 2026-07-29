package com.campusmesh.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Campus Mesh brand theme — GCTU's official blue and gold, replacing the old terminal
// black/neon-green look. Values match campus_mesh_design_mockups.html exactly.
// Blue = primary accent (headers, buttons, links, sent-message tint).
// Gold = secondary accent (badges, pills, avatar ring, CTAs) — used sparingly, not as a base color.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3E7BEA),            // Brand blue, brightened for dark-bg contrast
    onPrimary = Color(0xFFEEF1F6),
    primaryContainer = Color(0xFF1B355E),   // Outgoing message bubble
    onPrimaryContainer = Color(0xFFEEF1F6),
    secondary = Color(0xFFE3B655),          // Brand gold
    onSecondary = Color(0xFF3A2A05),
    secondaryContainer = Color(0xFF3A2D12), // Gold-tinted pill/badge background
    onSecondaryContainer = Color(0xFFF2C766),
    background = Color(0xFF0B1526),
    onBackground = Color(0xFFEEF1F6),
    surface = Color(0xFF13203A),
    onSurface = Color(0xFFEEF1F6),
    surfaceVariant = Color(0xFF22314F),     // Incoming bubble / row surface
    onSurfaceVariant = Color(0xFFA7B0C0),
    outline = Color(0xFF22314F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2C0B0B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF17458F),            // Brand blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FA),   // Outgoing message bubble
    onPrimaryContainer = Color(0xFF17458F),
    secondary = Color(0xFFD8A73D),          // Brand gold
    onSecondary = Color(0xFF3A2A05),
    secondaryContainer = Color(0xFFFBEFD1), // Gold-tinted pill/badge background
    onSecondaryContainer = Color(0xFF8A6314),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1A2233),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A2233),
    surfaceVariant = Color(0xFFE5E9F0),     // Incoming bubble border / row surface
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE5E9F0),
    error = Color(0xFFCC0000),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
