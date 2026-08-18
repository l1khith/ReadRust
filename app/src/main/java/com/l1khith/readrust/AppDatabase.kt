package com.l1khith.readrust

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.l1khith.readrust.data.BookmarkDao
import com.l1khith.readrust.data.BookmarkEntity
import com.l1khith.readrust.data.HighlightDao
import com.l1khith.readrust.data.HighlightEntity

/**
 * AppDatabase: The single, application-wide Room database instance.
 * Registered with BookEntity, HighlightEntity, and BookmarkEntity.
 */
@Database(
    entities = [
        BookEntity::class,
        HighlightEntity::class,
        BookmarkEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun highlightDao(): HighlightDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reader_database"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
