package com.l1khith.readrust

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.l1khith.readrust.ui.theme.AccentColor
import com.l1khith.readrust.ui.theme.AppBackground
import com.l1khith.readrust.ui.theme.SurfaceDark
import com.l1khith.readrust.ui.theme.TextGrey
import com.l1khith.readrust.ui.theme.TextWhite

/**
 * Asynchronously loads and displays a PDF first-page thumbnail.
 * Shows a loading spinner while generating, falls back to a book icon on failure.
 */
@Composable
fun PdfThumbnail(
    uriString: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, uriString) {
        value = withContext(Dispatchers.IO) {
            ThumbnailCache.generateAndCache(context, uriString.toUri())
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C2C2C)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = thumbnail != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            thumbnail?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PDF thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (thumbnail == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = "PDF",
                tint = AccentColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun LibraryScreen(
    onBookClick: (Uri) -> Unit,
    onAddBookClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Live Flow updates from Room database
    val allBooks by BookStore.getAllBooksFlow(context).collectAsState(initial = emptyList())
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // VIEW MODE STATE: default is List view ("list by list"), toggleable to Grid
    var isGridView by rememberSaveable { mutableStateOf(false) }

    // DELETE DIALOG STATE
    var bookToDelete by remember { mutableStateOf<BookData?>(null) }

    val filteredBooks = remember(allBooks, searchQuery) {
        if (searchQuery.isBlank()) allBooks
        else allBooks.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val recentBook = allBooks.firstOrNull()

    // DELETE CONFIRMATION DIALOG
    if (bookToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Delete Book?", color = TextWhite) },
            text = { Text("Are you sure you want to remove '${bookToDelete?.title}' from your library?", color = TextGrey) },
            containerColor = SurfaceDark,
            confirmButton = {
                Button(
                    onClick = {
                        bookToDelete?.let { book ->
                            BookStore.deleteBook(context, book.uriString, scope)
                        }
                        bookToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("Cancel", color = TextWhite)
                }
            }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddBookClick,
                containerColor = AccentColor,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add PDF")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { ReadingModeManager.isReadingMode = !ReadingModeManager.isReadingMode }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Toggle Reading Mode",
                            tint = if (ReadingModeManager.isReadingMode) AccentColor else TextGrey
                        )
                    }
                    Box(modifier = Modifier.size(32.dp).background(Color.Gray, CircleShape))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search your library...", color = TextGrey) },
                leadingIcon = { Icon(Icons.Default.Search, "Search", tint = TextGrey) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Continue Reading
            if (recentBook != null && searchQuery.isBlank()) {
                Text("Continue Reading", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(10.dp))
                ContinueReadingCard(
                    book = recentBook,
                    onClick = { onBookClick(recentBook.uriString.toUri()) },
                    onLongClick = { bookToDelete = recentBook }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Section Header & View Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("All Documents", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = { isGridView = !isGridView }) {
                    Icon(
                        imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = "Toggle View Layout",
                        tint = TextWhite
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (filteredBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books found. Tap + to add one!", color = TextGrey)
                }
            } else if (isGridView) {
                // GRID VIEW LAYOUT
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredBooks, key = { it.uriString }) { book ->
                        BookGridItem(
                            book = book,
                            onClick = { onBookClick(book.uriString.toUri()) },
                            onLongClick = { bookToDelete = book }
                        )
                    }
                }
            } else {
                // LIST BY LIST LAYOUT (Row by Row)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredBooks, key = { it.uriString }) { book ->
                        BookListItem(
                            book = book,
                            onClick = { onBookClick(book.uriString.toUri()) },
                            onLongClick = { bookToDelete = book }
                        )
                    }
                }
            }
        }
    }
}

// LIST ITEM COMPOSABLE (Row-by-row layout)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(book: BookData, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2C2C)),
            contentAlignment = Alignment.Center
        ) {
            PdfThumbnail(
                uriString = book.uriString,
                modifier = Modifier.fillMaxSize(),
                iconSize = 24.dp
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = book.title,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    progress = { book.getProgress() },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AccentColor,
                    trackColor = Color.DarkGray,
                )
                Spacer(modifier = Modifier.width(12.dp))
                val percent = (book.getProgress() * 100).toInt()
                Text(
                    text = "$percent% • p.${book.currentPage + 1}/${book.totalPages}",
                    color = TextGrey,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// GRID ITEM COMPOSABLE
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridItem(book: BookData, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(SurfaceDark, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            PdfThumbnail(
                uriString = book.uriString,
                modifier = Modifier.fillMaxSize(),
                iconSize = 48.dp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = book.title,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val progressPercent = (book.getProgress() * 100).toInt()
        Text(
            text = "$progressPercent% Read",
            color = AccentColor,
            fontSize = 12.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueReadingCard(book: BookData, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(70.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2C2C)),
            contentAlignment = Alignment.Center
        ) {
            PdfThumbnail(
                uriString = book.uriString,
                modifier = Modifier.fillMaxSize(),
                iconSize = 32.dp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                book.title,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { book.getProgress() },
                    modifier = Modifier.width(100.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AccentColor,
                    trackColor = Color.DarkGray,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val percent = (book.getProgress() * 100).toInt()
                Text("$percent%", color = AccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
