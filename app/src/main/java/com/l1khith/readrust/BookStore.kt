package com.l1khith.readrust

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
     * Save or update a book's reading progress.
     * Stores the ORIGINAL uri.toString() as the primary key so it matches permission grants.
     */
    fun saveBookProgress(
        context: Context,
        uri: Uri,
        page: Int,
        totalPages: Int,
        title: String? = null,
        scope: CoroutineScope
    ) {
        val key = uri.toString()
        scope.launch(Dispatchers.IO) {
            val book = BookEntity(
                uriString = key,
                title = title ?: uri.lastPathSegment ?: "Unknown Document",
                currentPage = page,
                totalPages = totalPages,
                lastReadTimestamp = System.currentTimeMillis()
            )
            dao(context).upsertBook(book)
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
    suspend fun getBook(context: Context, uriString: String): BookEntity? {
        val exact = dao(context).getBook(uriString)
        if (exact != null) return exact
        val target = normalizeForComparison(uriString)
        return dao(context).getAllBooksList().firstOrNull {
            normalizeForComparison(it.uriString) == target
        }
    }

    /**
     * Deletes a book and any legacy duplicate rows that share the same normalized URI.
     */
    fun deleteBook(context: Context, uriString: String, scope: CoroutineScope) {
        val target = normalizeForComparison(uriString)
        scope.launch(Dispatchers.IO) {
            val allBooks = dao(context).getAllBooksList()
            allBooks.filter { normalizeForComparison(it.uriString) == target }
                .forEach { dao(context).deleteBook(it.uriString) }
        }
    }
}
