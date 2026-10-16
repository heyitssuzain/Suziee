package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SuzieeDarkColorScheme = darkColorScheme(
    primary = SuzieeAccentPrimary,
    onPrimary = SuzieeBgPrimary,
    primaryContainer = SuzieeSurfaceElevated,
    onPrimaryContainer = SuzieeHighlight,
    secondary = SuzieeAccentSecondary,
    onSecondary = SuzieeBgPrimary,
    secondaryContainer = SuzieeSurfaceElevated,
    onSecondaryContainer = SuzieeHighlight,
    tertiary = SuzieeAccentSecondary,
    background = SuzieeBgPrimary,
    onBackground = SuzieeTextPrimary,
    surface = SuzieeBgSecondary,
    onSurface = SuzieeTextPrimary,
    surfaceVariant = SuzieeSurfaceElevated,
    onSurfaceVariant = SuzieeTextSecondary,
    outline = SuzieeBorder,
    error = SuzieeError
)

@Composable
fun SuzieeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = SuzieeDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = SuzieeBgPrimary.toArgb()
                window.navigationBarColor = SuzieeBgPrimary.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
