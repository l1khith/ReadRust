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

    /**
     * Decodes a URI string fully for COMPARISON purposes only.
     * Never use the output as a content:// URI for ContentResolver — it won't match permission grants.
     */
    private fun normalizeForComparison(uriString: String): String {
        return Uri.decode(uriString)
        // BG-1: Single decode is sufficient; double-decode corrupts %25 filenames
    }

    /**
     * Save or update a book's reading progress safely as a suspending function on Dispatchers.IO.
     * Stores the ORIGINAL uri.toString() as the primary key so it matches permission grants.
     */
    suspend fun saveBookProgress(
        context: Context,
        uri: Uri,
        page: Int,
        totalPages: Int,
        title: String? = null
    ) = withContext(Dispatchers.IO) {
        val key = uri.toString()
        val book = BookEntity(
            uriString = key,
            title = title ?: uri.lastPathSegment ?: "Unknown Document",
            currentPage = page,
            totalPages = totalPages,
            lastReadTimestamp = System.currentTimeMillis()
        )
        dao(context).upsertBook(book)
    }

    /**
     * Backward-compatible overload for non-suspending call sites.
     */
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
     * Returns a Flow of all books, deduplicated by normalized URI so legacy double-encoded
     * rows collapse into a single card. The FIRST entry (most recently read) is kept.
     */
    fun getAllBooksFlow(context: Context): Flow<List<BookEntity>> =
        dao(context).getAllBooksFlow().map { list ->
            list.distinctBy { normalizeForComparison(it.uriString) }
        }

    /**
     * One-shot read for page restoration. Tries the exact key first,
     * then falls back to a normalized search across all rows.
     */
    suspend fun getBook(context: Context, uriString: String): BookEntity? = withContext(Dispatchers.IO) {
        val exact = dao(context).getBook(uriString)
        if (exact != null) return@withContext exact
        val target = normalizeForComparison(uriString)
        dao(context).getAllBooksList().firstOrNull {
            normalizeForComparison(it.uriString) == target
        }
    }

    /**
     * Deletes a book and any legacy duplicate rows that share the same normalized URI safely on Dispatchers.IO.
     */
    suspend fun deleteBook(context: Context, uriString: String) = withContext(Dispatchers.IO) {
        val target = normalizeForComparison(uriString)
        val allBooks = dao(context).getAllBooksList()
        allBooks.filter { normalizeForComparison(it.uriString) == target }
            .forEach { dao(context).deleteBook(it.uriString) }
    }

    /**
     * Backward-compatible overload for non-suspending call sites.
     */
    fun deleteBook(context: Context, uriString: String, scope: CoroutineScope) {
        scope.launch {
            deleteBook(context, uriString)
        }
    }
}
