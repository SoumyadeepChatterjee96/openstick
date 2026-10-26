package org.openstick.ui.theme

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

// AMOLED Pitch Black Palette
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF000000)
val AmoledContainer = Color(0xFF0D0D0D)
val AmoledContainerHigh = Color(0xFF161616)
val AmoledBorder = Color(0xFF242424)
val AmoledText = Color(0xFFEDE9E4)
val AmoledTextDim = Color(0xFFA9A29A)

// Glassmorphism Frosted Layers (modern transparent aesthetic with high contrast text)
val AmoledGlassContainer = Color(0xCC0D0D0D)
val AmoledGlassHigh = Color(0xDD161616)
val AmoledGlassBorder = Color(0x2EFFFFFF)

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
