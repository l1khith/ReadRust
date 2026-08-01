package com.l1khith.readrust.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import com.l1khith.readrust.ReadingModeManager

private val AppColorScheme = darkColorScheme()

@Composable
fun ReadRustTheme(content: @Composable () -> Unit) {
    ReaderTheme(content = content)
}

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (ReadingModeManager.isReadingMode) {
                        drawRect(color = Color(0xFFE5A93B).copy(alpha = 0.15f))
                    }
                }
        ) {
            content()
        }
    }
}