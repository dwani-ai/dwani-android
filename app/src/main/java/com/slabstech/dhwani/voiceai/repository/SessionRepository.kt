package com.slabstech.dhwani.voiceai.repository

import android.content.Context
import android.net.Uri
import com.slabstech.dhwani.voiceai.Message
import com.slabstech.dhwani.voiceai.db.ChatMessage
import com.slabstech.dhwani.voiceai.db.ChatMessageDao
import com.slabstech.dhwani.voiceai.db.ChatSession
import com.slabstech.dhwani.voiceai.db.ChatSessionDao
import com.slabstech.dhwani.voiceai.db.DatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SessionRepository(private val context: Context) {

    private val db = DatabaseHolder.get(context)
    private val sessionDao: ChatSessionDao = db.chatSessionDao()
    private val messageDao: ChatMessageDao = db.chatMessageDao()

    private val attachmentsDir: File
        get() = File(context.filesDir, ATTACHMENTS_DIR).apply { if (!exists()) mkdirs() }

    fun getSessions(type: SessionType): Flow<List<ChatSession>> {
        return sessionDao.getSessionsByType(type.value)
    }

    fun getMessages(sessionId: String): Flow<List<Message>> {
        return messageDao.getMessagesBySessionId(sessionId).map { list ->
            list.map { it.toMessage(context) }
        }
    }

    suspend fun getOrCreateCurrentSession(type: SessionType): ChatSession {
        val existing = sessionDao.getMostRecentSessionByType(type.value)
        return if (existing != null) existing else createSession(type, null)
    }

    suspend fun createSession(type: SessionType, title: String? = null): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            type = type.value,
            title = title,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(session)
        return session
    }

    suspend fun getSession(sessionId: String): ChatSession? = sessionDao.getSessionById(sessionId)

    suspend fun addMessage(
        sessionId: String,
        text: String,
        timestamp: String,
        isQuery: Boolean,
        localFilePath: String? = null,
        fileType: String? = null,
        messageId: String? = null
    ): ChatMessage {
        val sortOrder = messageDao.getNextSortOrder(sessionId)
        val id = messageId ?: UUID.randomUUID().toString()
        val message = ChatMessage(
            id = id,
            sessionId = sessionId,
            text = text,
            timestamp = timestamp,
            isQuery = isQuery,
            localFilePath = localFilePath,
            fileType = fileType,
            sortOrder = sortOrder
        )
        messageDao.insert(message)
        updateSessionUpdatedAt(sessionId)
        if (sortOrder == 0 && isQuery) {
            updateSessionTitleFromFirstQuery(sessionId, text)
        }
        return message
    }

    private suspend fun updateSessionTitleFromFirstQuery(sessionId: String, messageText: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        if (session.title != null) return
        val title = messageText
            .replace(Regex("^(Query:|Input:|Voice Query:)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Image translation\\.\\.\\.?$", RegexOption.IGNORE_CASE), "Image")
            .trim()
            .take(MAX_SESSION_TITLE_LENGTH)
            .ifBlank { "New chat" }
        sessionDao.update(session.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addMessageWithAttachment(
        sessionId: String,
        text: String,
        timestamp: String,
        isQuery: Boolean,
        uri: Uri,
        fileType: String
    ): ChatMessage {
        val messageId = UUID.randomUUID().toString()
        val path = copyAttachmentToStorage(sessionId, messageId, uri, fileType)
        return addMessage(
            sessionId = sessionId,
            text = text,
            timestamp = timestamp,
            isQuery = isQuery,
            localFilePath = path,
            fileType = fileType,
            messageId = messageId
        )
    }

    suspend fun deleteMessage(messageId: String) {
        val message = messageDao.getMessageById(messageId) ?: return
        deleteMessageFile(message)
        messageDao.deleteById(messageId)
        updateSessionUpdatedAt(message.sessionId)
    }

    private suspend fun deleteMessageFile(message: ChatMessage) {
        message.localFilePath ?: return
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, message.localFilePath)
            if (file.exists()) file.delete()
        }
    }

    suspend fun deleteSession(sessionId: String) {
        messageDao.deleteBySessionId(sessionId)
        sessionDao.deleteById(sessionId)
        File(attachmentsDir, sessionId).takeIf { it.exists() }?.deleteRecursively()
    }

    suspend fun deleteAllMessagesInSession(sessionId: String) {
        val messages = messageDao.getMessagesBySessionIdOnce(sessionId)
        withContext(Dispatchers.IO) {
            messages.forEach { deleteMessageFile(it) }
        }
        messageDao.deleteBySessionId(sessionId)
        updateSessionUpdatedAt(sessionId)
    }

    /**
     * Copies content from [uri] to app storage under attachments/[sessionId]/[messageId].[ext].
     * Returns the relative path (e.g. "attachments/sessionId/messageId.jpg") or null on failure.
     */
    suspend fun copyAttachmentToStorage(
        sessionId: String,
        messageId: String,
        uri: Uri,
        fileType: String
    ): String? = withContext(Dispatchers.IO) {
        val ext = when (fileType.lowercase()) {
            "image" -> "jpg"
            "audio" -> "mp3"
            "pdf" -> "pdf"
            else -> "bin"
        }
        val sessionDir = File(attachmentsDir, sessionId).apply { mkdirs() }
        val destFile = File(sessionDir, "$messageId.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Return path relative to filesDir so we can rebuild: File(context.filesDir, path)
            File(ATTACHMENTS_DIR, "$sessionId/$messageId.$ext").path
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun updateSessionUpdatedAt(sessionId: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        sessionDao.update(session.copy(updatedAt = System.currentTimeMillis()))
    }

    companion object {
        private const val ATTACHMENTS_DIR = "attachments"
        private const val MAX_SESSION_TITLE_LENGTH = 50
    }
}

private fun ChatMessage.toMessage(context: Context): Message {
    val uri = localFilePath?.let { path ->
        val file = File(context.filesDir, path)
        if (file.exists()) Uri.fromFile(file) else null
    }
    return Message(
        text = text,
        timestamp = timestamp,
        isQuery = isQuery,
        uri = uri,
        fileType = fileType,
        id = id
    )
}
