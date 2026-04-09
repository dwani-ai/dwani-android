package com.slabstech.dhwani.voiceai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey
    val id: String,
    val type: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long
)
