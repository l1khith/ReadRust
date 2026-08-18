package com.l1khith.readrust.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.l1khith.readrust.ThemeManager

// Dynamic color properties linked directly to active ThemeManager theme
val AppBackground: Color @Composable get() = ThemeManager.AppBackground
val SurfaceDark: Color @Composable get() = ThemeManager.SurfaceDark
val SurfaceContainer: Color @Composable get() = ThemeManager.SurfaceDark
val AccentColor: Color @Composable get() = ThemeManager.AccentColor
val TextWhite: Color @Composable get() = ThemeManager.TextWhite
val TextGrey: Color @Composable get() = ThemeManager.TextGrey
val BorderColor: Color @Composable get() = ThemeManager.BorderColor