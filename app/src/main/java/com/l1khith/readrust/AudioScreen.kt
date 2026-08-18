package com.l1khith.readrust

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.BorderColor
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object BackgroundExportManager {
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val isExportingState = MutableStateFlow(false)
    val exportProgressCurrentState = MutableStateFlow(0)
    val exportProgressTotalState = MutableStateFlow(1)

    fun startExport(
        context: Context,
        book: BookData,
        speechRate: Float,
        onComplete: (File?) -> Unit
    ) {
        if (isExportingState.value) return

        exportScope.launch {
            isExportingState.value = true
            exportProgressCurrentState.value = 0
            exportProgressTotalState.value = book.totalPages.coerceAtLeast(1)

            val openRes = PdfHelper.openDocument(context, book.uriString.toUri())
            val handle = if (openRes is PdfResult.Success) PdfHelper.getDocHandle() else 0L

            try {
                val exportedFile = AudioExporter.exportPdfToAudioStream(
                    context = context,
                    pdfTitle = book.title,
                    totalPages = book.totalPages.coerceAtLeast(1),
                    docHandle = handle,
                    fetchPageSentences = { pageIndex ->
                        val res = PdfHelper.extractSentencesWithBoundsFromPage(pageIndex)
                        if (res is PdfResult.Success) res.value.map { it.text } else emptyList()
                    },
                    speechRate = speechRate,
                    onProgress = { curPage, totalPages ->
                        exportProgressCurrentState.value = curPage
                        exportProgressTotalState.value = totalPages
                    }
                )
                withContext(Dispatchers.Main) {
                    isExportingState.value = false
                    onComplete(exportedFile)
                }
            } finally {
                PdfHelper.closeDocument()
                isExportingState.value = false
            }
        }
    }
}

fun formatMsToMinutesSeconds(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun AudioScreen() {
    val context = LocalContext.current

    val allBooks by BookStore.getAllBooksFlow(context).collectAsState(initial = emptyList())
    var selectedBook by remember { mutableStateOf<BookData?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // Collect Background Exporter process states so navigation never cancels synthesis!
    val isExporting by BackgroundExportManager.isExportingState.collectAsState()
    val exportProgressCurrent by BackgroundExportManager.exportProgressCurrentState.collectAsState()
    val exportProgressTotal by BackgroundExportManager.exportProgressTotalState.collectAsState()

    var exportedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    // Spotify-like Player States
    var activeAudioFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPlayerPrepared by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    fun refreshExportedFiles() {
        exportedFiles = AudioExporter.getExportedAudioFiles(context)
    }

    fun applyPlaybackSpeed(player: MediaPlayer?, speed: Float) {
        if (player != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            } catch (_: Exception) {}
        }
    }

    fun playAudioFile(file: File) {
        try {
            isPlayerPrepared = false
            isPlaying = false
            mediaPlayer?.release()
            activeAudioFile = file
            val newPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    isPlayerPrepared = true
                    try {
                        mp.start()
                        applyPlaybackSpeed(mp, playbackSpeed)
                        isPlaying = true
                        durationMs = mp.duration.toLong().coerceAtLeast(0L)
                        currentPositionMs = 0L
                    } catch (e: Exception) {
                        android.util.Log.e("AudioScreen", "Failed to start player onPrepared", e)
                        isPlaying = false
                    }
                }
                setOnCompletionListener {
                    isPlaying = false
                    currentPositionMs = durationMs
                }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("AudioScreen", "MediaPlayer error: what=$what, extra=$extra")
                    isPlayerPrepared = false
                    isPlaying = false
                    true
                }
                prepareAsync()
            }
            mediaPlayer = newPlayer
        } catch (e: Exception) {
            isPlayerPrepared = false
            isPlaying = false
            Toast.makeText(context, "Error playing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun safeStartPlayer() {
        val player = mediaPlayer
        val file = activeAudioFile
        if (player != null && isPlayerPrepared) {
            try {
                player.start()
                applyPlaybackSpeed(player, playbackSpeed)
                isPlaying = true
            } catch (e: Exception) {
                android.util.Log.e("AudioScreen", "safeStartPlayer exception", e)
                isPlaying = false
                if (file != null) playAudioFile(file)
            }
        } else if (file != null) {
            playAudioFile(file)
        }
    }

    fun safePausePlayer() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            android.util.Log.e("AudioScreen", "safePausePlayer exception", e)
        } finally {
            isPlaying = false
        }
    }

    fun togglePlayback(file: File) {
        if (activeAudioFile?.absolutePath == file.absolutePath && mediaPlayer != null && isPlayerPrepared) {
            if (isPlaying) {
                safePausePlayer()
            } else {
                safeStartPlayer()
            }
        } else {
            playAudioFile(file)
        }
    }

    LaunchedEffect(Unit) {
        refreshExportedFiles()
    }

    LaunchedEffect(allBooks) {
        if (selectedBook == null && allBooks.isNotEmpty()) {
            selectedBook = allBooks.first()
        }
    }

    // Polling loop for audio progress bar
    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isActive && isPlaying && mediaPlayer != null) {
            try {
                mediaPlayer?.let { player ->
                    currentPositionMs = player.currentPosition.toLong()
                    durationMs = player.duration.toLong().coerceAtLeast(0L)
                }
            } catch (_: Exception) {}
            delay(300)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // DELETE CONFIRMATION DIALOG
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = {
                Text(
                    "Delete Audio File?",
                    color = TextWhite,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you sure you want to delete '${fileToDelete?.name}'?",
                    color = TextGrey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(32.dp),
            confirmButton = {
                Button(
                    onClick = {
                        val target = fileToDelete
                        if (target != null) {
                            if (activeAudioFile?.absolutePath == target.absolutePath) {
                                try {
                                    mediaPlayer?.stop()
                                    mediaPlayer?.release()
                                } catch (_: Exception) {}
                                mediaPlayer = null
                                activeAudioFile = null
                                isPlaying = false
                            }
                            AudioExporter.deleteAudioFile(target)
                            refreshExportedFiles()
                            Toast.makeText(context, "Deleted ${target.name}", Toast.LENGTH_SHORT).show()
                        }
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF93000A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Delete", color = Color(0xFFFFDAD6), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel", color = TextWhite, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Headphones, contentDescription = null, tint = AccentColor, modifier = Modifier.size(28.dp))
            Column {
                Text(
                    "Audio Studio",
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    "BACKGROUND PDF TO WAV AUDIO EXPORTER",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentColor,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // PDF Document Selector Box
        Text(
            "SELECT DOCUMENT TO EXPORT",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextGrey,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                .clickable { isDropdownExpanded = true }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedBook?.title ?: "Select a document...",
                    color = TextWhite,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AccentColor)
            }

            DropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier
                    .background(SurfaceDark)
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            ) {
                allBooks.forEach { book ->
                    DropdownMenuItem(
                        text = {
                            Text(book.title, color = TextWhite, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        },
                        onClick = {
                            selectedBook = book
                            isDropdownExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Export Button (Runs safely in Background Process Scope!)
        Button(
            onClick = {
                val book = selectedBook ?: return@Button
                Toast.makeText(context, "Exporting audio in background process...", Toast.LENGTH_SHORT).show()
                BackgroundExportManager.startExport(
                    context = context,
                    book = book,
                    speechRate = SettingsManager.defaultSpeechRate,
                    onComplete = { exportedFile ->
                        if (exportedFile != null && exportedFile.exists()) {
                            Toast.makeText(context, "Export complete: ${exportedFile.name}", Toast.LENGTH_LONG).show()
                            refreshExportedFiles()
                        } else {
                            Toast.makeText(context, "Failed to export audio file.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            },
            enabled = !isExporting && selectedBook != null,
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color(0xFF0A0A0A)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (isExporting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = Color(0xFF0A0A0A), modifier = Modifier.size(20.dp))
                    Text("EXPORTING PAGE $exportProgressCurrent / $exportProgressTotal...", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("EXPORT PDF TO WAV AUDIO", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (isExporting) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (exportProgressTotal > 0) exportProgressCurrent.toFloat() / exportProgressTotal.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentColor,
                trackColor = Color(0xFF2C2C2C)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── SPOTIFY-LIKE AUDIO PLAYER CARD ──
        if (activeAudioFile != null) {
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AccentColor, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Track Title Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = null, tint = AccentColor)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeAudioFile?.name ?: "Audio Track",
                                color = TextWhite,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isPlaying) "NOW PLAYING" else "PAUSED",
                                color = AccentColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress Slider
                    Slider(
                        value = if (durationMs > 0) currentPositionMs.coerceIn(0L, durationMs).toFloat() else 0f,
                        onValueChange = { pos ->
                            currentPositionMs = pos.toLong()
                            mediaPlayer?.seekTo(pos.toInt())
                        },
                        valueRange = 0f..(durationMs.coerceAtLeast(1L).toFloat()),
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor,
                            inactiveTrackColor = Color(0xFF2C2C2C)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatMsToMinutesSeconds(currentPositionMs),
                            color = TextGrey,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            formatMsToMinutesSeconds(durationMs),
                            color = TextGrey,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Spotify Controls: -5s, Play/Pause, +5s, Speed Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed Toggle Pill Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF131313))
                                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                .clickable {
                                    playbackSpeed = when (playbackSpeed) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 1.0f
                                    }
                                    applyPlaybackSpeed(mediaPlayer, playbackSpeed)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "${"%.2f".format(playbackSpeed)}x",
                                color = AccentColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Rewind -5 Seconds
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF2A2A2A))
                                .clickable {
                                    val target = (currentPositionMs - 5000L).coerceAtLeast(0L)
                                    currentPositionMs = target
                                    mediaPlayer?.seekTo(target.toInt())
                                }
                                .padding(10.dp)
                        ) {
                            Text(
                                "-5s",
                                color = TextWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Prominent Play/Pause Button
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(AccentColor)
                                .clickable {
                                    if (isPlaying) safePausePlayer() else safeStartPlayer()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color(0xFF0A0A0A),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Forward +5 Seconds
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF2A2A2A))
                                .clickable {
                                    val target = (currentPositionMs + 5000L).coerceAtMost(durationMs)
                                    currentPositionMs = target
                                    mediaPlayer?.seekTo(target.toInt())
                                }
                                .padding(10.dp)
                        ) {
                            Text(
                                "+5s",
                                color = TextWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Saved Audio Files List
        Text(
            "SAVED AUDIO FILES",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextGrey,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (exportedFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No exported WAV audio files found.\nSelect a document above and tap Export!",
                    color = TextGrey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(exportedFiles, key = { it.absolutePath }) { file ->
                    val isThisActive = activeAudioFile?.absolutePath == file.absolutePath
                    val isThisPlaying = isThisActive && isPlaying

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .border(1.dp, if (isThisActive) AccentColor else BorderColor, RoundedCornerShape(16.dp))
                            .clickable {
                                togglePlayback(file)
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.name,
                                color = TextWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val sizeMb = file.length() / (1024f * 1024f)
                            Text(
                                "${"%.2f".format(sizeMb)} MB • WAV Audio",
                                color = TextGrey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Play/Pause Icon
                            IconButton(
                                onClick = {
                                    togglePlayback(file)
                                }
                            ) {
                                Icon(
                                    imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = AccentColor
                                )
                            }

                            // Delete (Opens Confirmation Dialog!)
                            IconButton(
                                onClick = {
                                    fileToDelete = file
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = TextGrey
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
