package com.slabstech.dhwani.voiceai.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChatSession)

    @Query("SELECT * FROM chat_sessions WHERE type = :type ORDER BY updatedAt DESC")
    fun getSessionsByType(type: String): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE type = :type ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecentSessionByType(type: String): ChatSession?

    @Update
    suspend fun update(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)
}
