package com.l1khith.readrust.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.l1khith.readrust.BookEntity

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["uriString"],
            childColumns = ["bookUri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookUri"), Index("pageIndex")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
)
