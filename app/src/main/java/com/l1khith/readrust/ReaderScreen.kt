package com.l1khith.readrust

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.AppBackground
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite
import com.l1khith.readrust.ReadingModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToggleButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) AccentColor else Color.Transparent
    val textColor = if (isSelected) Color.White else TextGrey

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            if (isBound) {
                context.unbindService(connection)
            }

            val isCurrentlyPlaying = readerService?.isPlayingFlow?.value ?: false
            if (!isCurrentlyPlaying) {
                context.stopService(serviceIntent)
            }
        }
    }

    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var sentencesWithBounds by remember { mutableStateOf<List<SentenceWithBounds>>(emptyList()) }
    var totalPages by remember { mutableIntStateOf(1) }
    var isVisualMode by remember { mutableStateOf(true) }
    var documentReady by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalPages })

    val currentSentenceIndex by readerService?.currentSentenceIndexFlow?.collectAsState(initial = 0) ?: remember { mutableIntStateOf(0) }
    val isPlaying by readerService?.isPlayingFlow?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

    val textListState = rememberLazyListState()

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
                val result = PdfHelper.openDocument(context, uri)
                val saved  = BookStore.getBook(context, uri.toString())
                withContext(Dispatchers.Main) {
                    when (result) {
                        is PdfResult.Success -> {
                            if (result.value > 0) totalPages = result.value
                            documentReady = true
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
                    if (saved != null) currentPage = saved.currentPage
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

    LaunchedEffect(uri, isBound) {
        if (!isBound) return@LaunchedEffect

        snapshotFlow { currentPage }
            .debounce(500)
            .collect { page ->
                BookStore.saveBookProgress(
                    context, uri, page, totalPages, cachedFileName,
                    scope = this
                )
            }
    }

    LaunchedEffect(uri, isBound) {
        if (!isBound) return@LaunchedEffect

        snapshotFlow { currentPage }
            .distinctUntilChanged()
            .collectLatest { page ->
                withContext(Dispatchers.IO) {
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
        if (!isVisualMode && sentences.isNotEmpty()) {
            textListState.animateScrollToItem(currentSentenceIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (currentPage != pagerState.currentPage) {
            currentPage = pagerState.currentPage
        }
    }

    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.scrollToPage(currentPage)
        }
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reader", color = TextWhite, fontSize = 16.sp)
                        Text("Page ${currentPage + 1} of $totalPages", color = TextGrey, fontSize = 12.sp)
                    }
                },
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
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (isVisualMode) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f),
                    beyondViewportPageCount = 1
                ) { pageIndex ->
                    val pageRender by produceState<PageRender?>(initialValue = null, pageIndex, documentReady) {
                        if (!documentReady) {
                            value = null
                            return@produceState
                        }
                        ensureActive()
                        val result = withContext(Dispatchers.IO) {
                            PdfHelper.renderPageToBitmap(pageIndex)
                        }
                        value = if (result is PdfResult.Success) result.value else null
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageRender != null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = pageRender!!.bitmap.asImageBitmap(),
                                    contentDescription = "Page ${pageIndex + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )

                                // Highlight active reading sentence on top of PDF page in Visual mode ONLY when playing!
                                if (isPlaying && pageIndex == currentPage && currentSentenceIndex in sentencesWithBounds.indices) {
                                    val active = sentencesWithBounds[currentSentenceIndex]
                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                        val leftPx = active.left * size.width
                                        val topPx = active.top * size.height
                                        val rightPx = active.right * size.width
                                        val bottomPx = active.bottom * size.height

                                        val rectW = (rightPx - leftPx).coerceAtLeast(12f)
                                        val rectH = (bottomPx - topPx).coerceAtLeast(12f)

                                        drawRoundRect(
                                            color = Color(0x55007AFF), // Translucent Accent Blue highlight
                                            topLeft = androidx.compose.ui.geometry.Offset(leftPx, topPx),
                                            size = androidx.compose.ui.geometry.Size(rectW, rectH),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                        )
                                    }
                                }
                            }
                        } else {
                            CircularProgressIndicator(color = AccentColor)
                        }
                    }
                }
            } else {
                if (sentences.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "This page has no readable text. It may be a scanned image.",
                            color = TextGrey,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = textListState,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 16.dp)
                    ) {
                        itemsIndexed(sentences, key = { index, _ -> "sentence_$index" }) { index, sentence ->
                            val isActive = index == currentSentenceIndex
                            val itemColor = if (isActive) AccentColor else TextWhite
                            val bgColor = if (isActive) SurfaceDark else Color.Transparent
                            val weight = if (isActive) FontWeight.Bold else FontWeight.Normal

                            Text(
                                text = sentence,
                                color = itemColor,
                                fontSize = 18.sp,
                                fontWeight = weight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .clickable { readerService?.playSentence(index) }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SurfaceDark).padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                AudioControlPanel(readerService = readerService)

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalIconButton(onClick = { if (currentPage > 0) currentPage-- }) {
                        Icon(Icons.Default.Remove, "Prev")
                    }
                    Text("${currentPage + 1} / $totalPages", color = TextWhite)
                    FilledTonalIconButton(onClick = { if (currentPage < totalPages - 1) currentPage++ }) {
                        Icon(Icons.Default.Add, "Next")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp)).padding(4.dp)
                    ) {
                        ToggleButton(text = "Visual", isSelected = isVisualMode) { isVisualMode = true }
                        ToggleButton(text = "Text", isSelected = !isVisualMode) { isVisualMode = false }
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
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
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White)
                    }
                }
            }
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
                "Voice Settings",
                style = MaterialTheme.typography.titleSmall,
                color = TextGrey
            )
            IconButton(onClick = { isExpanded = !isExpanded }) {
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite
                )
                Text(
                    "${"%.1f".format(speechRate)}x",
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite
                )
                Text(
                    "%.1f".format(pitch),
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
