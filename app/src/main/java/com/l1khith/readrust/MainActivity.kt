package com.l1khith.readrust

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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

        // Initialize the Rust PDFium bridge engine
        NativePdfEngine.initEngineOnce()
        Log.i("MainActivity", "PDFium bridge engine initialized")

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

                // 4. Navigate (Uri.encode is required by Compose Navigation for content:// URIs with slashes)
                val encodedUri = Uri.encode(it.toString())
                navController.navigate("reader/$encodedUri")
            }
        }
    )

    NavHost(navController = navController, startDestination = "library") {

        // --- SCREEN 1: LIBRARY (HOME) ---
        composable("library") {
            LibraryScreen(
                onBookClick = { uri ->
                    val encodedUri = Uri.encode(uri.toString())
                    navController.navigate("reader/$encodedUri")
                },
                onAddBookClick = {
                    pdfLauncher.launch(arrayOf("application/pdf"))
                }
            )
        }

        // --- SCREEN 2: READER (PLAYER) ---
        composable(
            route = "reader/{uriString}",
            arguments = listOf(navArgument("uriString") { type = NavType.StringType })
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uriString")

            if (uriString != null) {
                val uri = Uri.parse(uriString)

                ReaderScreen(
                    uri = uri,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        // --- SCREEN 3: ABOUT ---
        composable("about") {
            AboutScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}