package com.slabstech.dhwani.voiceai

import com.slabstech.dhwani.voiceai.repository.SessionType
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTypeTest {

    @Test
    fun sessionType_valuesMatchExpected() {
        assertEquals("answer", SessionType.ANSWER.value)
        assertEquals("translate", SessionType.TRANSLATE.value)
        assertEquals("docs", SessionType.DOCS.value)
    }

    @Test
    fun sessionType_enumValues() {
        val types = SessionType.entries
        assertEquals(3, types.size)
        assert(types.contains(SessionType.ANSWER))
        assert(types.contains(SessionType.TRANSLATE))
        assert(types.contains(SessionType.DOCS))
    }
}
