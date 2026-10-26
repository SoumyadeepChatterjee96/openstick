package org.openstick.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppAccent(
    val title: String,
    val color: Color,
    val onColor: Color,
    val glowColor: Color
) {
    CYAN("Electric Cyan", Color(0xFF00E5FF), Color(0xFF00363D), Color(0x3300E5FF)),
    EMERALD("Emerald Neon", Color(0xFF00E676), Color(0xFF00391A), Color(0x3300E676)),
    VIOLET("Electric Violet", Color(0xFFB388FF), Color(0xFF2E0066), Color(0x33B388FF)),
    AMBER("Amber Glow", Color(0xFFFFB300), Color(0xFF3E2700), Color(0x33FFB300)),
    CORAL("Sunset Coral", Color(0xFFFF5252), Color(0xFF4A0000), Color(0x33FF5252))
}

enum class UiScale(val title: String, val factor: Float, val description: String) {
    COMPACT("Compact (85%)", 0.85f, "Maximum vertical space & data density"),
    STANDARD("Standard (100%)", 1.00f, "Balanced default scale"),
    MEDIUM("Medium (115%)", 1.15f, "Comfortable touch targets & text"),
    LARGE("Large (130%)", 1.30f, "Higher readability & navigation ease"),
    EXTRA_LARGE("Extra Large (150%)", 1.50f, "Maximum accessibility & finger comfort")
}

data class OpenStickPalette(
    val background: Color,
    val surface: Color,
    val container: Color,
    val containerHigh: Color,
    val glassContainer: Color,
    val glassHigh: Color,
    val border: Color,
    val glassBorder: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val onAccent: Color,
    val isDark: Boolean
)

val LocalOpenStickPalette = staticCompositionLocalOf {
    OpenStickPalette(
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
        accent = AmoledAccent,
        onAccent = AmoledOnAccent,
        isDark = true
    )
}

// AMOLED Pitch Black Palette
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF000000)
val AmoledContainer = Color(0xFF0D0D0D)
val AmoledContainerHigh = Color(0xFF161616)
val AmoledBorder = Color(0xFF242424)
val AmoledText = Color(0xFFEDE9E4)
val AmoledTextDim = Color(0xFFA9A29A)

// Glassmorphism Frosted Layers (modern transparent aesthetic)
val AmoledGlassContainer = Color(0xCC0D0D0D)
val AmoledGlassHigh = Color(0xDD161616)
val AmoledGlassBorder = Color(0x2EFFFFFF)

// Dracula Official Theme Palette
val DraculaBackground = Color(0xFF282A36)
val DraculaSurface = Color(0xFF21222C)
val DraculaContainer = Color(0xFF44475A)
val DraculaContainerHigh = Color(0xFF6272A4)
val DraculaBorder = Color(0xFF6272A4)
val DraculaText = Color(0xFFF8F8F2)
val DraculaTextDim = Color(0xFFB0B6CE)
val DraculaGlassContainer = Color(0xCC282A36)
val DraculaGlassHigh = Color(0xDD44475A)
val DraculaGlassBorder = Color(0x44BD93F9)

// Fallback legacy defaults
val AmoledAccent = Color(0xFF00E5FF)
val AmoledOnAccent = Color(0xFF00363D)

// Classic Dark Palette
val DarkBackground = Color(0xFF141414)
val DarkSurface = Color(0xFF1A1A1A)
val DarkContainer = Color(0xFF222222)
val DarkBorder = Color(0xFF333333)
val DarkAccent = Color(0xFF00E5FF)
val DarkOnAccent = Color(0xFF00363D)
val DarkText = Color(0xFFEDE9E4)
val DarkTextDim = Color(0xFFA9A29A)

// Light Palette
val LightBackground = Color(0xFFF6F4F1)
val LightSurface = Color(0xFFFFFFFF)
val LightContainer = Color(0xFFF0EAE3)
val LightBorder = Color(0xFFDDD7CF)
val LightAccent = Color(0xFF00838F)
val LightOnAccent = Color(0xFFFFFFFF)
val LightText = Color(0xFF262320)
val LightTextDim = Color(0xFF6E6862)
