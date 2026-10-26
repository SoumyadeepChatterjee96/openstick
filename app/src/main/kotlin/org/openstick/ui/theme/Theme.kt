package org.openstick.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode {
    SYSTEM,
    AMOLED_BLACK,
    DRACULA,
    DARK,
    LIGHT
}

@Composable
fun OpenStickTheme(
    themeMode: AppThemeMode = AppThemeMode.AMOLED_BLACK,
    accent: AppAccent = AppAccent.CYAN,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.AMOLED_BLACK -> true
        AppThemeMode.DRACULA -> true
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }

    val palette = when {
        themeMode == AppThemeMode.AMOLED_BLACK -> OpenStickPalette(
            background = AmoledBackground,
            surface = AmoledSurface,
            container = AmoledContainer,
            containerHigh = AmoledContainerHigh,
            glassContainer = AmoledGlassContainer,
            glassHigh = AmoledGlassHigh,
            border = AmoledBorder,
            glassBorder = AmoledGlassBorder,
            text = AmoledText,
            textDim = AmoledTextDim,
            accent = accent.color,
            onAccent = accent.onColor,
            isDark = true
        )
        themeMode == AppThemeMode.DRACULA -> OpenStickPalette(
            background = DraculaBackground,
            surface = DraculaSurface,
            container = DraculaContainer,
            containerHigh = DraculaContainerHigh,
            glassContainer = DraculaGlassContainer,
            glassHigh = DraculaGlassHigh,
            border = DraculaBorder,
            glassBorder = DraculaGlassBorder,
            text = DraculaText,
            textDim = DraculaTextDim,
            accent = accent.color,
            onAccent = accent.onColor,
            isDark = true
        )
        themeMode == AppThemeMode.LIGHT || (!systemDark && themeMode == AppThemeMode.SYSTEM) -> OpenStickPalette(
            background = LightBackground,
            surface = LightSurface,
            container = LightContainer,
            containerHigh = Color(0xFFE2DDD5),
            glassContainer = Color(0xF0FFFFFF),
            glassHigh = Color(0xF6F2ECE4),
            border = LightBorder,
            glassBorder = Color(0x2B000000),
            text = LightText,
            textDim = LightTextDim,
            accent = accent.color,
            onAccent = accent.onColor,
            isDark = false
        )
        else -> OpenStickPalette(
            background = DarkBackground,
            surface = DarkSurface,
            container = DarkContainer,
            containerHigh = Color(0xFF2C2C2C),
            glassContainer = Color(0xCC1A1A1A),
            glassHigh = Color(0xDD282828),
            border = DarkBorder,
            glassBorder = Color(0x33FFFFFF),
            text = DarkText,
            textDim = DarkTextDim,
            accent = accent.color,
            onAccent = accent.onColor,
            isDark = true
        )
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

    val draculaScheme = darkColorScheme(
        primary = accent.color,
        onPrimary = accent.onColor,
        primaryContainer = DraculaContainer,
        onPrimaryContainer = DraculaText,
        secondary = Color(0xFFBD93F9),
        onSecondary = Color(0xFF282A36),
        background = DraculaBackground,
        onBackground = DraculaText,
        surface = DraculaSurface,
        onSurface = DraculaText,
        surfaceVariant = DraculaContainer,
        onSurfaceVariant = DraculaTextDim,
        outline = DraculaBorder
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
        themeMode == AppThemeMode.DRACULA -> draculaScheme
        !palette.isDark -> lightScheme
        else -> darkScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = palette.background.toArgb()
                window.navigationBarColor = palette.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !palette.isDark
                controller.isAppearanceLightNavigationBars = !palette.isDark
            }
        }
    }

    CompositionLocalProvider(LocalOpenStickPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
