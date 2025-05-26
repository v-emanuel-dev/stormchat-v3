package com.ivip.brainstormia.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        ConversationMetadataEntity::class,
        ModelPreferenceEntity::class  // Add this line
    ],
    version = 2,  // Increment version
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun conversationMetadataDao(): ConversationMetadataDao
    abstract fun modelPreferenceDao(): ModelPreferenceDao  // Add this line

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "brainstormia_database"
                )
                    .fallbackToDestructiveMigration()  // For simplicity, you may want a proper migration in production
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}