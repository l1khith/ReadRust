package com.l1khith.readrust

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.l1khith.readrust.ui.theme.ReadRustTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("MainActivity", "ReadRust app started")
        SettingsManager.init(this)

        setContent {
            ReadRustTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Launcher for picking PDFs
    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                // 1. Grant permissions
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w("MainActivity", "Persistable URI permission not granted: ${e.message}")
                }

                // 2. Get the REAL file name
                val realName = FileNameUtils.getFileName(context, it)

                // 3. Save to BookStore immediately so Library sees it
                BookStore.saveBookProgress(context, it, 0, 1, realName, scope)

                // 4. Navigate with singleTop to prevent backstack duplicates
                val encodedUri = Uri.encode(it.toString())
                navController.navigate("reader/$encodedUri") {
                    launchSingleTop = true
                }
            }
        }
    )

    NavHost(navController = navController, startDestination = "main") {

        // --- MAIN CONTAINER SCREEN (LIBRARY, AUDIO, SETTINGS BOTTOM NAV) ---
        composable("main") {
            MainScreen(
                onBookClick = { uri ->
                    val encodedUri = Uri.encode(uri.toString())
                    navController.navigate("reader/$encodedUri") {
                        launchSingleTop = true
                    }
                },
                onAddBookClick = {
                    pdfLauncher.launch(arrayOf("application/pdf"))
                }
            )
        }

        // --- READER (PLAYER) ---
        composable(
            route = "reader/{uriString}",
            arguments = listOf(navArgument("uriString") { type = NavType.StringType })
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uriString")

            if (!uriString.isNullOrBlank()) {
                val uri = Uri.parse(uriString)

                ReaderScreen(
                    uri = uri,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Invalid PDF URI")
                }
            }
        }

        // --- ABOUT ---
        composable("about") {
            AboutScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}