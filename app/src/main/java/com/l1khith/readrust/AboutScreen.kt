package com.l1khith.readrust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import com.l1khith.readrust.ReadingModeManager
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.AppBackground
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite

/**
 * AboutScreen — Displays app information and open-source license attributions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("About", color = TextWhite, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { ReadingModeManager.isReadingMode = !ReadingModeManager.isReadingMode }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Toggle Reading Mode",
                            tint = if (ReadingModeManager.isReadingMode) AccentColor else TextGrey
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            // ── App Info ──
            Text(
                text = "ReadRust",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Version 1.0",
                color = TextGrey,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "A Rust-powered PDF reader with text-to-speech playback.",
                color = TextGrey,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SurfaceDark)
            Spacer(modifier = Modifier.height(24.dp))

            // ── Open Source Licenses ──
            Text(
                text = "Open Source Licenses",
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            LicenseItem(
                name = "Android Jetpack",
                copyright = "© Google LLC",
                license = "Apache License 2.0",
                description = "AndroidX libraries including Compose, Room, Navigation, and Media."
            )
            LicenseItem(
                name = "Kotlin",
                copyright = "© JetBrains s.r.o.",
                license = "Apache License 2.0",
                description = "Programming language and standard library."
            )
            LicenseItem(
                name = "Material Components for Android",
                copyright = "© Google LLC",
                license = "Apache License 2.0",
                description = "Material Design 3 UI components."
            )
            LicenseItem(
                name = "Coil",
                copyright = "© Coil Contributors",
                license = "Apache License 2.0",
                description = "Image loading library for Compose."
            )
            LicenseItem(
                name = "Gson",
                copyright = "© Google Inc.",
                license = "Apache License 2.0",
                description = "JSON serialization/deserialization library."
            )
        }
    }
}

@Composable
private fun LicenseItem(
    name: String,
    copyright: String,
    license: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Text(text = name, color = AccentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = copyright, color = TextGrey, fontSize = 12.sp)
        Text(text = license, color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = description, color = TextWhite, fontSize = 13.sp)
    }
}
