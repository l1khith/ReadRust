package com.l1khith.readrust

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.AppBackground
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey

@Composable
fun MainScreen(
    onBookClick: (Uri) -> Unit,
    onAddBookClick: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = AppBackground,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp
            ) {
                // Tab 0: Library
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Library"
                        )
                    },
                    label = {
                        Text(
                            "Library",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A0A0A),
                        selectedTextColor = AccentColor,
                        indicatorColor = AccentColor,
                        unselectedIconColor = TextGrey,
                        unselectedTextColor = TextGrey
                    )
                )

                // Tab 1: Audio
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = "Audio"
                        )
                    },
                    label = {
                        Text(
                            "Audio",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A0A0A),
                        selectedTextColor = AccentColor,
                        indicatorColor = AccentColor,
                        unselectedIconColor = TextGrey,
                        unselectedTextColor = TextGrey
                    )
                )

                // Tab 2: Settings
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = {
                        Text(
                            "Settings",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A0A0A),
                        selectedTextColor = AccentColor,
                        indicatorColor = AccentColor,
                        unselectedIconColor = TextGrey,
                        unselectedTextColor = TextGrey
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> LibraryScreen(onBookClick = onBookClick, onAddBookClick = onAddBookClick)
                1 -> AudioScreen()
                2 -> SettingsScreen()
            }
        }
    }
}
