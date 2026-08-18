package com.l1khith.readrust

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val displayName: String,
    val background: Color,
    val surface: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color
) {
    DARK(
        displayName = "Dark (Developer)",
        background = Color(0xFF131313),
        surface = Color(0xFF1C1B1B),
        accent = Color(0xFFFFB74D),
        textPrimary = Color(0xFFE5E2E1),
        textSecondary = Color(0xFF9E8E7C),
        border = Color(0xFF514536)
    ),
    LIGHT(
        displayName = "Light",
        background = Color(0xFFF8F9FA),
        surface = Color(0xFFFFFFFF),
        accent = Color(0xFFD97706),
        textPrimary = Color(0xFF1E293B),
        textSecondary = Color(0xFF64748B),
        border = Color(0xFFCBD5E1)
    ),
    SEPIA(
        displayName = "Sepia",
        background = Color(0xFFF4ECD8),
        surface = Color(0xFFEAE0C8),
        accent = Color(0xFFB45309),
        textPrimary = Color(0xFF433422),
        textSecondary = Color(0xFF786043),
        border = Color(0xFFD4C5A9)
    ),
    SLATE(
        displayName = "Slate",
        background = Color(0xFF0F172A),
        surface = Color(0xFF1E293B),
        accent = Color(0xFF38BDF8),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        border = Color(0xFF334155)
    ),
    OLED(
        displayName = "OLED (Pure Black)",
        background = Color(0xFF000000),
        surface = Color(0xFF121212),
        accent = Color(0xFFFFB74D),
        textPrimary = Color(0xFFE5E5E5),
        textSecondary = Color(0xFF888888),
        border = Color(0xFF262626)
    ),
    REVERSE(
        displayName = "Reverse (High Contrast)",
        background = Color(0xFF050505),
        surface = Color(0xFF181818),
        accent = Color(0xFF00E5FF),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFAAAAAA),
        border = Color(0xFF00E5FF)
    )
}

object ThemeManager {
    var currentTheme by mutableStateOf(AppTheme.DARK)

    val AppBackground: Color get() = currentTheme.background
    val SurfaceDark: Color get() = currentTheme.surface
    val AccentColor: Color get() = currentTheme.accent
    val TextWhite: Color get() = currentTheme.textPrimary
    val TextGrey: Color get() = currentTheme.textSecondary
    val BorderColor: Color get() = currentTheme.border
}
