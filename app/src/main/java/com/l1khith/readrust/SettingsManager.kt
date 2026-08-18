package com.l1khith.readrust

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

enum class AppFontFamily(val displayName: String, val fontFamily: FontFamily) {
    SERIF("Merriweather (Serif)", FontFamily.Serif),
    SANS_SERIF("Inter (Sans-Serif)", FontFamily.SansSerif),
    MONOSPACE("JetBrains Mono (Monospace)", FontFamily.Monospace),
    CURSIVE("Cursive", FontFamily.Cursive)
}

object SettingsManager {
    var selectedFontFamily by mutableStateOf(AppFontFamily.SERIF)
    var fontSizeSp by mutableFloatStateOf(16f)
    var isPaperTextureEnabled by mutableStateOf(true)
    var isPremiumUser by mutableStateOf(false)
    var defaultSpeechRate by mutableFloatStateOf(1.0f)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("readrust_settings", Context.MODE_PRIVATE)
        val fontName = prefs.getString("font_family", AppFontFamily.SERIF.name) ?: AppFontFamily.SERIF.name
        selectedFontFamily = try { AppFontFamily.valueOf(fontName) } catch (_: Exception) { AppFontFamily.SERIF }
        fontSizeSp = prefs.getFloat("font_size", 16f)
        isPaperTextureEnabled = prefs.getBoolean("paper_texture", true)
        isPremiumUser = prefs.getBoolean("is_premium", false)
        defaultSpeechRate = prefs.getFloat("speech_rate", 1.0f)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences("readrust_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("font_family", selectedFontFamily.name)
            .putFloat("font_size", fontSizeSp)
            .putBoolean("paper_texture", isPaperTextureEnabled)
            .putBoolean("is_premium", isPremiumUser)
            .putFloat("speech_rate", defaultSpeechRate)
            .apply()
    }
}
