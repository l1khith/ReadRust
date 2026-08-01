package com.l1khith.readrust

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global manager for app-wide Reading Mode (Eye Comfort Shield).
 * Toggling this applies a warm amber filter over the entire app view.
 */
object ReadingModeManager {
    var isReadingMode by mutableStateOf(false)
}
