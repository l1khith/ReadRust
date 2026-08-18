package com.l1khith.readrust

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BookEntity: The Room database model representing a single book in the user's library.
 * The [uriString] acts as the natural primary key since each book is uniquely identified by its URI.
 */
@Entity(
    tableName = "books",
    indices = [Index(value = ["lastReadTimestamp"])]
)
data class BookEntity(
    @PrimaryKey
    val uriString: String,
    val title: String,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val bookmarkedPages: String = "" // Comma-separated list of 0-based page indices (e.g. "0,3,7")
) {
    /** Returns reading progress as a float from 0.0 to 1.0. */
    fun getProgress(): Float = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f

    /** Returns set of bookmarked 0-based page indices. */
    fun getBookmarksSet(): Set<Int> {
        if (bookmarkedPages.isBlank()) return emptySet()
        return bookmarkedPages.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }
}
