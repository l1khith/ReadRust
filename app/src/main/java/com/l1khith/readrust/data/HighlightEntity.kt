package com.l1khith.readrust.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.l1khith.readrust.BookEntity

@Entity(
    tableName = "highlights",
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
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val pageIndex: Int,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val colorArgb: Int = 0xFFFFEB3B.toInt(), // Default yellow
    val boundsLeft: Float = 0f,
    val boundsTop: Float = 0f,
    val boundsRight: Float = 0f,
    val boundsBottom: Float = 0f,
    val textSnippet: String = "",
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
