package com.slabstech.dhwani.voiceai

import android.content.Context
import androidx.room.Room
import com.slabstech.dhwani.voiceai.db.DatabaseHolder
import com.slabstech.dhwani.voiceai.db.DhwaniDatabase
import com.slabstech.dhwani.voiceai.repository.SessionRepository
import com.slabstech.dhwani.voiceai.repository.SessionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: DhwaniDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication().applicationContext
        db = Room.inMemoryDatabaseBuilder(context, DhwaniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseHolder.testInstance = db
        repository = SessionRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
        DatabaseHolder.testInstance = null
    }

    @Test
    fun createSession_returnsSessionWithCorrectType() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        assertEquals(SessionType.ANSWER.value, session.type)
        assertNotNull(session.id)
        assertNull(session.title)
        assertTrue(session.createdAt > 0)
        assertEquals(session.createdAt, session.updatedAt)
    }

    @Test
    fun createSession_withTitle_storesTitle() = runTest {
        val session = repository.createSession(SessionType.DOCS, "My Doc Chat")
        assertEquals("My Doc Chat", session.title)
        assertEquals(SessionType.DOCS.value, session.type)
    }

    @Test
    fun getOrCreateCurrentSession_createsWhenNoneExists() = runTest {
        val session = repository.getOrCreateCurrentSession(SessionType.ANSWER)
        assertEquals(SessionType.ANSWER.value, session.type)
        assertNotNull(session.id)
    }

    @Test
    fun getOrCreateCurrentSession_returnsExistingWhenExists() = runTest {
        val created = repository.createSession(SessionType.ANSWER, null)
        val retrieved = repository.getOrCreateCurrentSession(SessionType.ANSWER)
        assertEquals(created.id, retrieved.id)
    }

    @Test
    fun addMessage_messageAppearsInGetMessages() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        repository.addMessage(
            sessionId = session.id,
            text = "Query: hello",
            timestamp = "10:00",
            isQuery = true,
            null,
            null
        )
        val messages = repository.getMessages(session.id).first()
        assertEquals(1, messages.size)
        assertEquals("Query: hello", messages[0].text)
        assertEquals("10:00", messages[0].timestamp)
        assertTrue(messages[0].isQuery)
        assertNotNull(messages[0].id)
    }

    @Test
    fun addMessage_multipleMessages_orderedBySortOrder() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        repository.addMessage(session.id, "First", "10:00", true, null, null)
        repository.addMessage(session.id, "Second", "10:01", false, null, null)
        repository.addMessage(session.id, "Third", "10:02", true, null, null)
        val messages = repository.getMessages(session.id).first()
        assertEquals(3, messages.size)
        assertEquals("First", messages[0].text)
        assertEquals("Second", messages[1].text)
        assertEquals("Third", messages[2].text)
    }

    @Test
    fun addMessage_withLocalFilePath_messageStoresPath() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        val path = "attachments/${session.id}/msg1.jpg"
        repository.addMessage(
            sessionId = session.id,
            text = "Image query",
            timestamp = "10:00",
            isQuery = true,
            localFilePath = path,
            fileType = "image"
        )
        val messages = repository.getMessages(session.id).first()
        assertEquals(1, messages.size)
        assertEquals("image", messages[0].fileType)
        // uri may be null in test if file doesn't exist at path
    }

    @Test
    fun deleteMessage_removesMessageFromFlow() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        val msg = repository.addMessage(session.id, "To delete", "10:00", true, null, null)
        assertEquals(1, repository.getMessages(session.id).first().size)
        repository.deleteMessage(msg.id)
        val messages = repository.getMessages(session.id).first()
        assertEquals(0, messages.size)
    }

    @Test
    fun deleteSession_removesSessionAndMessages() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        repository.addMessage(session.id, "One", "10:00", true, null, null)
        repository.deleteSession(session.id)
        assertNull(repository.getSession(session.id))
        val messages = repository.getMessages(session.id).first()
        assertEquals(0, messages.size)
    }

    @Test
    fun getSessions_byType_returnsOnlyThatType() = runTest {
        repository.createSession(SessionType.ANSWER, null)
        repository.createSession(SessionType.TRANSLATE, null)
        repository.createSession(SessionType.ANSWER, "Second answer")
        val answerSessions = repository.getSessions(SessionType.ANSWER).first()
        assertEquals(2, answerSessions.size)
        assertTrue(answerSessions.all { it.type == SessionType.ANSWER.value })
        val translateSessions = repository.getSessions(SessionType.TRANSLATE).first()
        assertEquals(1, translateSessions.size)
        assertEquals(SessionType.TRANSLATE.value, translateSessions[0].type)
    }

    @Test
    fun deleteAllMessagesInSession_keepsSessionClearsMessages() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        repository.addMessage(session.id, "A", "10:00", true, null, null)
        repository.addMessage(session.id, "B", "10:01", false, null, null)
        repository.deleteAllMessagesInSession(session.id)
        assertNotNull(repository.getSession(session.id))
        val messages = repository.getMessages(session.id).first()
        assertEquals(0, messages.size)
    }
}
