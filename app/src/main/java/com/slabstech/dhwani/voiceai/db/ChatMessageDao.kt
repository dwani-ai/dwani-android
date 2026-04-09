package com.slabstech.dhwani.voiceai.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY sortOrder ASC")
    fun getMessagesBySessionId(sessionId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY sortOrder ASC")
    suspend fun getMessagesBySessionIdOnce(sessionId: String): List<ChatMessage>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getNextSortOrder(sessionId: String): Int

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
