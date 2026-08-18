package com.l1khith.readrust

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.BorderColor
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showThemeDialog by remember { mutableStateOf(false) }

    // Real-time Storage calculation states
    var realTimeCacheBytes by remember { mutableLongStateOf(0L) }
    var realTimeTotalStorageBytes by remember { mutableLongStateOf(0L) }

    fun calculateStorageRealTime() {
        scope.launch(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val filesDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)

            val cacheSize = (cacheDir?.walkTopDown()?.sumOf { it.length() } ?: 0L) +
                    ThumbnailCache.getCacheSize(context)

            val totalFilesSize = (filesDir?.walkTopDown()?.sumOf { it.length() } ?: 0L) +
                    (externalDir?.walkTopDown()?.sumOf { it.length() } ?: 0L)

            withContext(Dispatchers.Main) {
                realTimeCacheBytes = cacheSize
                realTimeTotalStorageBytes = cacheSize + totalFilesSize
            }
        }
    }

    LaunchedEffect(Unit) {
        calculateStorageRealTime()
    }

    // THEME SELECTOR DIALOG
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = {
                Text("Reading Theme", color = TextWhite, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.values().forEach { theme ->
                        val isSelected = ThemeManager.currentTheme == theme
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) theme.accent.copy(alpha = 0.2f) else theme.surface)
                                .border(1.dp, if (isSelected) theme.accent else theme.border, RoundedCornerShape(16.dp))
                                .clickable {
                                    ThemeManager.currentTheme = theme
                                    showThemeDialog = false
                                }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                theme.displayName,
                                color = theme.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(theme.accent)
                            )
                        }
                    }
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(32.dp),
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close", color = AccentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            // Header
            Column {
                Text(
                    "Settings",
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    "CONFIGURATION & PREFERENCES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentColor,
                    letterSpacing = 1.sp
                )
            }
        }

        // ── APPEARANCE GROUP ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
                    Text("APPEARANCE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextGrey, fontWeight = FontWeight.Bold)
                }

                // Theme Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                        .clickable { showThemeDialog = true }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Reading Theme", color = TextWhite, fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(ThemeManager.currentTheme.displayName, color = TextGrey, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextGrey)
                }
            }
        }

        // ── REAL-TIME STORAGE GROUP ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SdCard, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
                    Text("REAL-TIME STORAGE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextGrey, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Cache Storage", color = TextWhite, fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val cacheMb = realTimeCacheBytes / (1024f * 1024f)
                            Text(
                                if (cacheMb < 0.1f) "%.1f KB".format(realTimeCacheBytes / 1024f) else "%.2f MB".format(cacheMb),
                                color = TextGrey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ThumbnailCache.clearCache(context)
                                    context.cacheDir?.deleteRecursively()
                                    context.cacheDir?.mkdirs()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Real-Time Cache Cleared!", Toast.LENGTH_SHORT).show()
                                        calculateStorageRealTime()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF93000A), contentColor = Color(0xFFFFDAD6)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("CLEAR CACHE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BorderColor)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Total App Data Storage", color = TextWhite, fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Includes PDFs, Database & Exported Audio", color = TextGrey, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        Text(
                            "%.2f MB".format(realTimeTotalStorageBytes / (1024f * 1024f)),
                            color = AccentColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // ── PREMIUM / AD-FREE GROUP ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
                    Text("READRUST PRO", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextGrey, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .border(1.dp, AccentColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ad-Free Premium Mode", color = TextWhite, fontFamily = FontFamily.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (SettingsManager.isPremiumUser) "PRO Activated • Unlimited Reading & Audio"
                                else "Free Tier: Ads after 7 pages read / 7 pages audio preview",
                                color = TextGrey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = SettingsManager.isPremiumUser,
                            onCheckedChange = {
                                SettingsManager.isPremiumUser = it
                                SettingsManager.save(context)
                                val status = if (it) "ReadRust PRO Activated!" else "Switched to Free Tier"
                                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0A0A0A), checkedTrackColor = AccentColor)
                        )
                    }
                }
            }
        }

        // ── ABOUT GROUP ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
                    Text("ABOUT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextGrey, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text("ReadRust v2.1 Developer Edition", color = TextWhite, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "High-performance Android PDF Reader built with Rust PDFium Native Engine and Jetpack Compose.",
                        color = TextGrey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
