package org.openstick.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode {
    SYSTEM,
    AMOLED_BLACK,
    DARK,
    LIGHT
}

@Composable
fun OpenStickTheme(
    themeMode: AppThemeMode = AppThemeMode.AMOLED_BLACK,
    accent: AppAccent = AppAccent.CYAN,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.AMOLED_BLACK -> true
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val amoledScheme = darkColorScheme(
        primary = accent.color,
        onPrimary = accent.onColor,
        primaryContainer = AmoledContainerHigh,
        onPrimaryContainer = AmoledText,
        secondary = accent.color,
        onSecondary = accent.onColor,
        background = AmoledBackground,
        onBackground = AmoledText,
        surface = AmoledSurface,
        onSurface = AmoledText,
        surfaceVariant = AmoledContainer,
        onSurfaceVariant = AmoledTextDim,
        outline = AmoledBorder
    )

    val darkScheme = darkColorScheme(
        primary = accent.color,
        onPrimary = accent.onColor,
        primaryContainer = DarkContainer,
        onPrimaryContainer = DarkText,
        secondary = accent.color,
        onSecondary = accent.onColor,
        background = DarkBackground,
        onBackground = DarkText,
        surface = DarkSurface,
        onSurface = DarkText,
        surfaceVariant = DarkContainer,
        onSurfaceVariant = DarkTextDim,
        outline = DarkBorder
    )

    val lightScheme = lightColorScheme(
        primary = accent.color,
        onPrimary = accent.onColor,
        primaryContainer = LightContainer,
        onPrimaryContainer = LightText,
        secondary = accent.color,
        onSecondary = accent.onColor,
        background = LightBackground,
        onBackground = LightText,
        surface = LightSurface,
        onSurface = LightText,
        surfaceVariant = LightContainer,
        onSurfaceVariant = LightTextDim,
        outline = LightBorder
    )

    val colorScheme = when {
        themeMode == AppThemeMode.AMOLED_BLACK -> amoledScheme
        darkTheme -> darkScheme
        else -> lightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
