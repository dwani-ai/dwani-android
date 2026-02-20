package com.slabstech.dhwani.voiceai

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.slabstech.dhwani.voiceai.db.DatabaseHolder
import com.slabstech.dhwani.voiceai.db.DhwaniDatabase
import com.slabstech.dhwani.voiceai.repository.SessionRepository
import com.slabstech.dhwani.voiceai.repository.SessionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Instrumented tests for SessionRepository. Run on device/emulator.
 * Uses in-memory DB via DatabaseHolder.testInstance so we don't touch production data.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryInstrumentedTest {

    private lateinit var db: DhwaniDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        db = Room.inMemoryDatabaseBuilder(context, DhwaniDatabase::class.java)
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
    fun createSession_andAddMessage_persistsAcrossFlow() = runTest {
        val session = repository.createSession(SessionType.ANSWER, null)
        repository.addMessage(session.id, "Query: test", "12:00", true, null, null)
        repository.addMessage(session.id, "Answer: result", "12:01", false, null, null)
        val messages = repository.getMessages(session.id).first()
        assertEquals(2, messages.size)
        assertEquals("Query: test", messages[0].text)
        assertEquals("Answer: result", messages[1].text)
        assertNotNull(messages[0].id)
        assertNotNull(messages[1].id)
    }

    @Test
    fun getOrCreateCurrentSession_returnsSameSessionOnSecondCall() = runTest {
        val s1 = repository.getOrCreateCurrentSession(SessionType.TRANSLATE)
        val s2 = repository.getOrCreateCurrentSession(SessionType.TRANSLATE)
        assertEquals(s1.id, s2.id)
    }
}
