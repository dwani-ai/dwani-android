package com.slabstech.dhwani.voiceai.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessage(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val text: String,
    val timestamp: String,
    val isQuery: Boolean,
    val localFilePath: String?,
    val fileType: String?,
    val sortOrder: Int
)
