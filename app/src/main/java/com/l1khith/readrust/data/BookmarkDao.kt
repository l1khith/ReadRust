package com.l1khith.readrust.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookUri = :bookUri ORDER BY pageIndex")
    fun getForBookFlow(bookUri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookUri = :bookUri ORDER BY pageIndex")
    suspend fun getForBook(bookUri: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE bookUri = :bookUri AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getForPage(bookUri: String, pageIndex: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookUri = :bookUri AND pageIndex = :pageIndex")
    suspend fun deleteForPage(bookUri: String, pageIndex: Int)
}
