package com.slabstech.dhwani.voiceai

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies Message data class backward compatibility: id is optional and defaults to null.
 */
class MessageTest {

    @Test
    fun message_constructorWithoutId_defaultsToNull() {
        val message = Message(
            text = "Hello",
            timestamp = "10:00",
            isQuery = true,
            uri = null,
            fileType = null
        )
        assertNull(message.id)
        assert(message.text == "Hello")
        assert(message.isQuery)
    }

    @Test
    fun message_constructorWithId_preservesId() {
        val message = Message(
            text = "Hi",
            timestamp = "10:01",
            isQuery = false,
            uri = null,
            fileType = null,
            id = "msg-123"
        )
        assert(message.id == "msg-123")
    }
}
