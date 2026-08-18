package com.l1khith.readrust.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookUri = :bookUri AND pageIndex = :pageIndex")
    fun getForPageFlow(bookUri: String, pageIndex: Int): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookUri = :bookUri AND pageIndex = :pageIndex")
    suspend fun getForPage(bookUri: String, pageIndex: Int): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookUri = :bookUri ORDER BY pageIndex, charStart")
    suspend fun getAllForBook(bookUri: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookUri = :bookUri ORDER BY pageIndex, charStart")
    fun getAllForBookFlow(bookUri: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :highlightId")
    suspend fun deleteById(highlightId: Long)
}
