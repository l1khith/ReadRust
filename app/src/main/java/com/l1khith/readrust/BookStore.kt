package com.l1khith.readrust

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias BookData = BookEntity

object BookStore {

    private fun dao(context: Context): BookDao =
        AppDatabase.getInstance(context).bookDao()

    private fun normalizeForComparison(uriString: String): String {
        return Uri.decode(uriString)
    }

    /**
     * Save or update a book's reading progress safely as a suspending function on Dispatchers.IO.
     * Preserves existing bookmarks if the book already exists.
     */
    suspend fun saveBookProgress(
        context: Context,
        uri: Uri,
        page: Int,
        totalPages: Int,
        title: String? = null
    ) = withContext(Dispatchers.IO) {
        val key = uri.toString()
        val existing = getBook(context, key)
        val book = BookEntity(
            uriString = key,
            title = title ?: existing?.title ?: uri.lastPathSegment ?: "Unknown Document",
            currentPage = page,
            totalPages = totalPages,
            lastReadTimestamp = System.currentTimeMillis(),
            bookmarkedPages = existing?.bookmarkedPages ?: ""
        )
        dao(context).upsertBook(book)
    }

    fun saveBookProgress(
        context: Context,
        uri: Uri,
        page: Int,
        totalPages: Int,
        title: String? = null,
        scope: CoroutineScope
    ) {
        scope.launch {
            saveBookProgress(context, uri, page, totalPages, title)
        }
    }

    /**
     * Toggles bookmark state for [page] (0-based) for the book with [uri].
     */
    suspend fun toggleBookmark(
        context: Context,
        uri: Uri,
        page: Int,
        totalPages: Int,
        title: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val key = uri.toString()
        val existing = getBook(context, key)
        val set = existing?.getBookmarksSet()?.toMutableSet() ?: mutableSetOf()
        val isNowBookmarked = if (set.contains(page)) {
            set.remove(page)
            false
        } else {
            set.add(page)
            true
        }

        val updatedString = set.sorted().joinToString(",")
        val updatedBook = BookEntity(
            uriString = key,
            title = title ?: existing?.title ?: uri.lastPathSegment ?: "Unknown Document",
            currentPage = existing?.currentPage ?: page,
            totalPages = totalPages,
            lastReadTimestamp = System.currentTimeMillis(),
            bookmarkedPages = updatedString
        )
        dao(context).upsertBook(updatedBook)
        isNowBookmarked
    }

    fun getAllBooksFlow(context: Context): Flow<List<BookEntity>> =
        dao(context).getAllBooksFlow().map { list ->
            list.distinctBy { normalizeForComparison(it.uriString) }
        }

    suspend fun getBook(context: Context, uriString: String): BookEntity? = withContext(Dispatchers.IO) {
        val exact = dao(context).getBook(uriString)
        if (exact != null) return@withContext exact
        val target = normalizeForComparison(uriString)
        dao(context).getAllBooksList().firstOrNull {
            normalizeForComparison(it.uriString) == target
        }
    }

    suspend fun deleteBook(context: Context, uriString: String) = withContext(Dispatchers.IO) {
        val target = normalizeForComparison(uriString)
        val allBooks = dao(context).getAllBooksList()
        allBooks.filter { normalizeForComparison(it.uriString) == target }
            .forEach { dao(context).deleteBook(it.uriString) }
    }

    fun deleteBook(context: Context, uriString: String, scope: CoroutineScope) {
        scope.launch {
            deleteBook(context, uriString)
        }
    }
}
