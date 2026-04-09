package com.slabstech.dhwani.voiceai.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatSession::class, ChatMessage::class],
    version = 1,
    exportSchema = false
)
abstract class DhwaniDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
}

object DatabaseHolder {
    @Volatile
    private var instance: DhwaniDatabase? = null

    /** For tests only: inject an in-memory or test DB. Call setTestInstance(null) in tearDown. */
    @Volatile
    var testInstance: DhwaniDatabase? = null

    fun get(context: Context): DhwaniDatabase {
        testInstance?.let { return it }
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DhwaniDatabase::class.java,
                "dhwani.db"
            ).build().also { instance = it }
        }
    }
}
