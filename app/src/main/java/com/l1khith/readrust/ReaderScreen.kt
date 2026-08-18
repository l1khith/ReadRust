package com.l1khith.readrust

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import java.io.File
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.AppBackground
import com.l1khith.readrust.ui.theme.BorderColor
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun ToggleButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) AccentColor else Color.Transparent
    val textColor = if (isSelected) Color(0xFF0A0A0A) else TextGrey

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    uri: Uri,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var readerService by remember { mutableStateOf<ReaderService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    val serviceIntent = remember { Intent(context, ReaderService::class.java) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as ReaderService.LocalBinder
                readerService = binder.getService()
                isBound = true
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                isBound = false
                readerService = null
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Notification permission denied — playback controls won't appear on lock screen",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start reader service: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        onDispose {
            if (isBound) {
                try { context.unbindService(connection) } catch (_: Exception) {}
            }

            val isCurrentlyPlaying = readerService?.isPlayingFlow?.value ?: false
            if (!isCurrentlyPlaying) {
                try { context.stopService(serviceIntent) } catch (_: Exception) {}
            }
        }
    }

    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var sentencesWithBounds by remember { mutableStateOf<List<SentenceWithBounds>>(emptyList()) }
    var totalPages by remember { mutableIntStateOf(1) }
    var isVisualMode by rememberSaveable { mutableStateOf(true) }

    // DEFAULT TO CONTINUOUS VERTICAL TOP-DOWN SCROLL MODE
    var isVerticalScroll by rememberSaveable { mutableStateOf(true) }

    var isPanelMinimized by rememberSaveable { mutableStateOf(false) }
    var showBookmarkMenuDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var documentReady by remember { mutableStateOf(false) }

    // ── SPOTIFY-STYLE 7-PAGE AUTOMATIC AD BREAK INTERSTITIAL STATES ──
    var showSpotifyAdBreakDialog by remember { mutableStateOf(false) }
    var lastAdShownPage by rememberSaveable { mutableIntStateOf(-1) }
    var adCountdownSeconds by remember { mutableIntStateOf(5) }

    val pagerState = rememberPagerState(initialPage = currentPage, pageCount = { totalPages })

    val currentSentenceIndex by readerService?.currentSentenceIndexFlow?.collectAsState(initial = 0) ?: remember { mutableIntStateOf(0) }
    val isPlaying by readerService?.isPlayingFlow?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

    val textListState = rememberLazyListState()

    val appDatabase = remember { AppDatabase.getInstance(context) }
    var showColorPickerSheet by remember { mutableStateOf(false) }
    var highlightSentenceText by remember { mutableStateOf("") }
    var selectedSentenceBounds by remember { mutableStateOf<SentenceWithBounds?>(null) }
    var showExportOptionsSheet by remember { mutableStateOf(false) }

    // Live Book state from Room for Bookmarks and Title
    val allBooks by BookStore.getAllBooksFlow(context).collectAsState(initial = emptyList())
    val currentBook = remember(allBooks, uri) {
        allBooks.firstOrNull { it.uriString == uri.toString() }
    }
    val pageHighlightsFlow = remember(currentBook, currentPage) {
        if (currentBook != null) appDatabase.highlightDao().getForPageFlow(currentBook.uriString, currentPage)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val pageHighlights by pageHighlightsFlow.collectAsState(initial = emptyList())
    val bookmarkedPagesSet = remember(currentBook) {
        currentBook?.getBookmarksSet() ?: emptySet()
    }
    val isCurrentPageBookmarked = currentPage in bookmarkedPagesSet

    // ── AUTOMATIC 7-PAGE SPOTIFY-STYLE AD TRIGGER (WITHOUT HUMAN INTERVENTION) ──
    LaunchedEffect(currentPage) {
        if (!SettingsManager.isPremiumUser && currentPage > 0 && (currentPage + 1) % 7 == 0 && lastAdShownPage != currentPage) {
            lastAdShownPage = currentPage
            adCountdownSeconds = 5
            showSpotifyAdBreakDialog = true
        }
    }

    // Automatic countdown loop for Spotify Ad Break
    LaunchedEffect(showSpotifyAdBreakDialog) {
        if (showSpotifyAdBreakDialog) {
            while (adCountdownSeconds > 0) {
                delay(1000)
                adCountdownSeconds--
            }
            showSpotifyAdBreakDialog = false
        }
    }

    LaunchedEffect(readerService) {
        readerService?.ttsErrorFlow?.collect { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(isBound) {
        if (isBound) {
            documentReady = false
            withContext(Dispatchers.IO) {
                try {
                    val result = PdfHelper.openDocument(context, uri)
                    val saved = BookStore.getBook(context, uri.toString())
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is PdfResult.Success -> {
                                if (result.value > 0) totalPages = result.value
                                documentReady = true
                                if (saved != null) {
                                    val targetPage = saved.currentPage.coerceIn(0, totalPages - 1)
                                    currentPage = targetPage
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(targetPage)
                                    }
                                }
                            }
                            is PdfResult.Error -> {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Cannot open PDF: ${result.message}",
                                        actionLabel = "Dismiss"
                                    )
                                }
                            }
                            is PdfResult.NotReady -> {}
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar("Error opening document: ${e.message}")
                    }
                }
            }
        }
    }

    LaunchedEffect(readerService, totalPages, currentPage) {
        readerService?.onPageEndReached = {
            if (currentPage < totalPages - 1) {
                currentPage++
            } else {
                readerService?.pauseAudio()
            }
        }
    }

    val cachedFileName by produceState(initialValue = "Loading...", uri) {
        value = withContext(Dispatchers.IO) {
            FileNameUtils.getFileName(context, uri)
        }
    }

    // Debounced page progress saving
    LaunchedEffect(uri, isBound) {
        if (!isBound) return@LaunchedEffect

        snapshotFlow { currentPage }
            .debounce(1000)
            .distinctUntilChanged()
            .collect { page ->
                BookStore.saveBookProgress(
                    context, uri, page, totalPages, cachedFileName
                )
            }
    }

    LaunchedEffect(uri, isBound) {
        if (!isBound) return@LaunchedEffect

        snapshotFlow { currentPage }
            .distinctUntilChanged()
            .collectLatest { page ->
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val result = PdfHelper.extractSentencesWithBoundsFromPage(page)
                    val items = when (result) {
                        is PdfResult.Success -> result.value
                        is PdfResult.Error   -> emptyList()
                        is PdfResult.NotReady -> emptyList()
                    }
                    val extracted = items.map { it.text }
                    withContext(Dispatchers.Main) {
                        sentencesWithBounds = items
                        sentences = extracted
                        readerService?.setSentences(extracted)
                    }
                }
            }
    }

    LaunchedEffect(currentSentenceIndex) {
        if (!isVisualMode && sentences.isNotEmpty() && currentSentenceIndex in sentences.indices) {
            textListState.animateScrollToItem(currentSentenceIndex)
        }
    }

    // Single source of truth for Pager State -> currentPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (isVisualMode && !isVerticalScroll && currentPage != page) {
                    currentPage = page
                }
            }
    }

    // Programmatic scroll to page when changed by Prev/Next buttons or initial load
    LaunchedEffect(currentPage, isVisualMode, isVerticalScroll) {
        if (isVisualMode && !isVerticalScroll && pagerState.currentPage != currentPage && currentPage in 0 until totalPages && !pagerState.isScrollInProgress) {
            try {
                pagerState.scrollToPage(currentPage)
            } catch (_: Exception) {}
        }
    }

    // ── SPOTIFY-STYLE AUTOMATIC 7-PAGE AD BREAK DIALOG ──
    if (showSpotifyAdBreakDialog) {
        AlertDialog(
            onDismissRequest = { showSpotifyAdBreakDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(AccentColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "AD BREAK",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0A0A0A)
                        )
                    }
                    Text(
                        "Spotify-Style Ad",
                        color = TextWhite,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Enjoy 7 pages of uninterrupted reading & audio!",
                        color = TextWhite,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "ca-app-pub-3940256099942544/1033173712 (Official Google AdMob Interstitial Test Ad). Ads support free reading on ReadRust.",
                        color = TextGrey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF131313))
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (adCountdownSeconds > 0) "Resuming reading in ${adCountdownSeconds}s..." else "Ready to continue!",
                            color = AccentColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(32.dp),
            confirmButton = {
                Button(
                    onClick = { showSpotifyAdBreakDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color(0xFF0A0A0A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (adCountdownSeconds > 0) "SKIP AD (${adCountdownSeconds}s)" else "CONTINUE READING",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        SettingsManager.isPremiumUser = true
                        showSpotifyAdBreakDialog = false
                        Toast.makeText(context, "ReadRust PRO Activated! Ads Removed.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = AccentColor, modifier = Modifier.size(16.dp))
                        Text("UPGRADE TO PRO", color = AccentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    // THEME SELECTION DIALOG (2.0rem / 32px rounded shape)
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = {
                Text(
                    "Select Theme",
                    color = TextWhite,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold
                )
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

    // UNIFIED SINGLE BOOKMARK DIALOG (2.0rem / 32px rounded container shape)
    if (showBookmarkMenuDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarkMenuDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bookmark & Lock Page",
                        color = TextWhite,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Icon(
                        imageVector = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = AccentColor
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Quick Action: Bookmark/Lock Current Page (1.0rem / 16px Pill shape)
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val isBookmarked = BookStore.toggleBookmark(context, uri, currentPage, totalPages, cachedFileName)
                                val msg = if (isBookmarked) "Page ${currentPage + 1} Bookmarked & Locked" else "Bookmark Removed"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCurrentPageBookmarked) Color(0xFF93000A) else AccentColor,
                            contentColor = if (isCurrentPageBookmarked) Color(0xFFFFDAD6) else Color(0xFF0A0A0A)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isCurrentPageBookmarked) "REMOVE BOOKMARK (PAGE ${currentPage + 1})" else "BOOKMARK & LOCK PAGE ${currentPage + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "SAVED BOOKMARKS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextGrey,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (bookmarkedPagesSet.isEmpty()) {
                        Text(
                            "No bookmarks saved for this document yet.",
                            color = TextGrey,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height((bookmarkedPagesSet.size * 52).coerceAtMost(220).dp)
                        ) {
                            itemsIndexed(bookmarkedPagesSet.sorted()) { _, pageIndex ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(SurfaceDark)
                                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                                        .clickable {
                                            currentPage = pageIndex
                                            showBookmarkMenuDialog = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Page ${pageIndex + 1}",
                                        color = TextWhite,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    BookStore.toggleBookmark(context, uri, pageIndex, totalPages, cachedFileName)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Remove", tint = TextGrey, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(32.dp),
            confirmButton = {
                TextButton(onClick = { showBookmarkMenuDialog = false }) {
                    Text("Close", color = AccentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth(0.7f)) {
                        Text(
                            text = cachedFileName,
                            color = TextWhite,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (totalPages > 0) "PAGE ${currentPage + 1} OF $totalPages" else "LOADING...",
                            color = AccentColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                actions = {
                    // Export Options Button
                    IconButton(onClick = { showExportOptionsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = "Export Options",
                            tint = AccentColor
                        )
                    }

                    // ONE UNIFIED BOOKMARK & LOCK BUTTON
                    IconButton(onClick = { showBookmarkMenuDialog = true }) {
                        Icon(
                            imageVector = if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmarks & Lock",
                            tint = if (isCurrentPageBookmarked) AccentColor else TextWhite
                        )
                    }

                    // Theme Selector Button
                    IconButton(onClick = { showThemeDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Select Theme",
                            tint = AccentColor
                        )
                    }

                    // Scroll Direction Toggle
                    IconButton(
                        onClick = { isVerticalScroll = !isVerticalScroll }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapVert,
                            contentDescription = "Toggle Scroll Direction",
                            tint = if (isVerticalScroll) AccentColor else TextGrey
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── Top PDF Reader Canvas (Default Top-Down Vertical Scroll) ──
            if (isVisualMode) {
                if (isVerticalScroll) {
                    VerticalScrollReader(
                        totalPages = totalPages,
                        currentPage = currentPage,
                        onPageChange = { currentPage = it },
                        documentReady = documentReady,
                        currentSentenceIndex = currentSentenceIndex,
                        sentencesWithBounds = sentencesWithBounds,
                        isPlaying = isPlaying
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1
                    ) { pageIndex ->
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }

                        LaunchedEffect(pageIndex) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }

                        val activeSentence = if (isPlaying && pageIndex == currentPage && currentSentenceIndex in sentencesWithBounds.indices) {
                            sentencesWithBounds[currentSentenceIndex]
                        } else null

                        val pageRender by produceState<PageRender?>(initialValue = null, pageIndex, documentReady, activeSentence) {
                            if (!documentReady) {
                                value = null
                                return@produceState
                            }
                            ensureActive()
                            val result = withContext(Dispatchers.IO) {
                                PdfHelper.renderPageToBitmap(pageIndex, activeSentence)
                            }
                            value = if (result is PdfResult.Success) result.value else null
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (pageRender != null) {
                                val pageModifier = if (scale > 1f) {
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        )
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                                scale = newScale
                                                if (newScale > 1f) {
                                                    offsetX += pan.x
                                                    offsetY += pan.y
                                                } else {
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                }
                                            }
                                        }
                                } else {
                                    Modifier.fillMaxSize()
                                }

                                Image(
                                    bitmap = pageRender!!.bitmap.asImageBitmap(),
                                    contentDescription = "Page ${pageIndex + 1} of $totalPages",
                                    modifier = pageModifier,
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                CircularProgressIndicator(color = AccentColor)
                            }
                        }
                    }
                }
            } else {
                if (sentences.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "This page has no readable text. It may be a scanned image.",
                            color = TextGrey,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = textListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        @OptIn(ExperimentalFoundationApi::class)
                        itemsIndexed(sentences, key = { index, _ -> "sentence_$index" }) { index, sentence ->
                            val isActive = index == currentSentenceIndex
                            val itemColor = if (isActive) Color(0xFF0A0A0A) else TextWhite
                            val bgColor = if (isActive) AccentColor else Color.Transparent
                            val weight = if (isActive) FontWeight.Bold else FontWeight.Normal

                            Text(
                                text = sentence,
                                color = itemColor,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 17.sp,
                                fontWeight = weight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(bgColor)
                                    .combinedClickable(
                                        onClick = { readerService?.playSentence(index) },
                                        onLongClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("PDF Text", sentence)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Copied text to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // ── Floating, Semi-Transparent, Minimizable Bottom Control Panel ──
            Surface(
                color = SurfaceDark.copy(alpha = 0.92f),
                shape = RoundedCornerShape(32.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .border(width = 1.dp, color = BorderColor, shape = RoundedCornerShape(32.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    // Control Panel Header with Minimize/Expand Toggle Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "CONTROLS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = AccentColor,
                                fontWeight = FontWeight.Bold
                            )
                            if (isPanelMinimized) {
                                Text(
                                    "• PAGE ${currentPage + 1} OF $totalPages",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isPanelMinimized) {
                                IconButton(
                                    onClick = {
                                        if (isPlaying) readerService?.pauseAudio()
                                        else readerService?.resumeAudio()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = AccentColor
                                    )
                                }
                            }
                            IconButton(
                                onClick = { isPanelMinimized = !isPanelMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPanelMinimized) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isPanelMinimized) "Expand Panel" else "Minimize Panel",
                                    tint = TextWhite
                                )
                            }
                        }
                    }

                    // Expanded Control Panel Options
                    AnimatedVisibility(visible = !isPanelMinimized) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            AudioControlPanel(readerService = readerService)

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalIconButton(
                                    onClick = { if (currentPage > 0) currentPage-- },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Default.Remove, "Prev", tint = TextWhite)
                                }
                                Text(
                                    "${currentPage + 1} / $totalPages",
                                    color = TextWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                FilledTonalIconButton(
                                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Default.Add, "Next", tint = TextWhite)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFF131313), RoundedCornerShape(16.dp))
                                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                                        .padding(4.dp)
                                ) {
                                    ToggleButton(text = "Visual", isSelected = isVisualMode) { isVisualMode = true }
                                    ToggleButton(text = "Text", isSelected = !isVisualMode) { isVisualMode = false }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(AccentColor, CircleShape)
                                        .clickable {
                                            if (isPlaying) {
                                                readerService?.pauseAudio()
                                            } else {
                                                if (sentences.isEmpty()) {
                                                    Toast.makeText(context, "No text found on this page to read aloud", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    readerService?.resumeAudio()
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color(0xFF0A0A0A))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showColorPickerSheet && selectedSentenceBounds != null) {
            com.l1khith.readrust.ui.components.ColorPickerSheet(
                sentenceText = highlightSentenceText,
                onColorSelected = { selectedColor ->
                    val bounds = selectedSentenceBounds
                    if (bounds != null && currentBook != null) {
                        coroutineScope.launch {
                            val colorInt = selectedColor.toArgb()
                            val highlight = com.l1khith.readrust.data.HighlightEntity(
                                bookUri = currentBook.uriString,
                                pageIndex = currentPage,
                                colorArgb = colorInt,
                                boundsLeft = bounds.left,
                                boundsTop = bounds.top,
                                boundsRight = bounds.right,
                                boundsBottom = bounds.bottom,
                                textSnippet = bounds.text
                            )
                            appDatabase.highlightDao().insert(highlight)
                            Toast.makeText(context, "Highlight Saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showColorPickerSheet = false
                },
                onDismiss = { showColorPickerSheet = false }
            )
        }

        if (showExportOptionsSheet) {
            com.l1khith.readrust.ui.export.ExportOptionsSheet(
                bookTitle = cachedFileName,
                onExportAudio = {
                    com.l1khith.readrust.tts.AudioExporterService.startExport(context, cachedFileName, totalPages)
                    Toast.makeText(context, "Audio export started in background...", Toast.LENGTH_SHORT).show()
                },
                onExportAnnotatedPdf = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val bookUri = uri.toString()
                        val highlights = appDatabase.highlightDao().getAllForBook(bookUri)
                        val bookmarks = appDatabase.bookmarkDao().getForBook(bookUri)

                        val gson = com.google.gson.Gson()
                        val hlJson = gson.toJson(highlights)
                        val bmJson = gson.toJson(bookmarks)

                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            val outputDir = File(context.getExternalFilesDir(null), "exported_pdfs").apply { mkdirs() }
                            val safeName = cachedFileName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val outputFile = File(outputDir, "${safeName}_annotated.pdf")

                            val res = com.l1khith.readrust.annotations.AnnotationNativeBridge.nativeExportAnnotatedPdf(
                                pfd.fd,
                                outputFile.absolutePath,
                                hlJson,
                                bmJson
                            )
                            pfd.close()

                            withContext(Dispatchers.Main) {
                                if (res == 0 && outputFile.exists()) {
                                    Toast.makeText(context, "Exported Annotated PDF: ${outputFile.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to export Annotated PDF", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                },
                onDismiss = { showExportOptionsSheet = false }
            )
        }
    }
}

@Composable
fun AudioControlPanel(
    readerService: ReaderService?,
    modifier: Modifier = Modifier
) {
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var pitch by remember { mutableFloatStateOf(1.0f) }
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(readerService) {
        readerService?.let {
            val (rate, p) = it.getAudioSettings()
            speechRate = rate
            pitch = p
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "VOICE SETTINGS",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = TextGrey,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = TextGrey
                )
            }
        }

        if (isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Speed",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite
                )
                Text(
                    "${"%.1f".format(speechRate)}x",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = speechRate,
                onValueChange = {
                    speechRate = it
                    readerService?.setSpeechRate(it)
                },
                valueRange = 0.5f..2.0f,
                steps = 5,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color(0xFF2C2C2C),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Pitch",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite
                )
                Text(
                    "%.1f".format(pitch),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = pitch,
                onValueChange = {
                    pitch = it
                    readerService?.setPitch(it)
                },
                valueRange = 0.5f..2.0f,
                steps = 5,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor,
                    inactiveTrackColor = Color(0xFF2C2C2C),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun VerticalScrollReader(
    totalPages: Int,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    documentReady: Boolean,
    currentSentenceIndex: Int,
    sentencesWithBounds: List<SentenceWithBounds>,
    isPlaying: Boolean
) {
    val listState = rememberLazyListState()

    // Track which page is most visible and sync to currentPage
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val viewportCenter =
                    (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    val itemCenter = item.offset + item.size / 2
                    abs(itemCenter - viewportCenter)
                }?.index ?: 0
            } else {
                listState.firstVisibleItemIndex
            }
        }
            .distinctUntilChanged()
            .collect { page ->
                if (page != currentPage && page in 0 until totalPages) {
                    onPageChange(page)
                }
            }
    }

    // Scroll to currentPage when changed externally (prev/next buttons)
    LaunchedEffect(currentPage) {
        if (currentPage in 0 until totalPages && !listState.isScrollInProgress) {
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
            if (currentPage < firstVisible || currentPage > lastVisible) {
                listState.scrollToItem(currentPage)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp), // Extra padding for floating control bar
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            count = totalPages,
            key = { index -> "vpage_$index" }
        ) { pageIndex ->
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }

            LaunchedEffect(pageIndex) {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            }

            val activeSentence =
                if (isPlaying && pageIndex == currentPage && currentSentenceIndex in sentencesWithBounds.indices) {
                    sentencesWithBounds[currentSentenceIndex]
                } else null

            val pageRender by produceState<PageRender?>(
                initialValue = null,
                pageIndex,
                documentReady,
                activeSentence
            ) {
                if (!documentReady) {
                    value = null
                    return@produceState
                }
                ensureActive()
                val result = withContext(Dispatchers.IO) {
                    PdfHelper.renderPageToBitmap(pageIndex, activeSentence)
                }
                value = if (result is PdfResult.Success) result.value else null
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (pageRender != null) {
                    val pageModifier = if (scale > 1f) {
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = newScale
                                    if (newScale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                    } else {
                        Modifier.fillMaxWidth()
                    }

                    Image(
                        bitmap = pageRender!!.bitmap.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1} of $totalPages",
                        modifier = pageModifier,
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    CircularProgressIndicator(color = AccentColor)
                }

                // Page number badge at bottom-right of each page (Pill shape 16px)
                Text(
                    text = "${pageIndex + 1} / $totalPages",
                    color = AccentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(SurfaceDark, RoundedCornerShape(16.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
