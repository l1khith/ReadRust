package com.l1khith.readrust.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.l1khith.readrust.AppTheme
import com.l1khith.readrust.ReadingModeManager
import com.l1khith.readrust.ThemeManager

@Composable
fun ReadRustTheme(content: @Composable () -> Unit) {
    ReaderTheme(content = content)
}

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    val current = ThemeManager.currentTheme
    val colorScheme = if (current == AppTheme.LIGHT || current == AppTheme.SEPIA) {
        lightColorScheme(
            primary = current.accent,
            background = current.background,
            surface = current.surface,
            onBackground = current.textPrimary,
            onSurface = current.textPrimary,
            outline = current.border
        )
    } else {
        darkColorScheme(
            primary = current.accent,
            background = current.background,
            surface = current.surface,
            onBackground = current.textPrimary,
            onSurface = current.textPrimary,
            outline = current.border
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (ReadingModeManager.isReadingMode) {
                        drawRect(color = current.accent.copy(alpha = 0.12f))
                    }
                }
        ) {
            content()
        }
    }
}