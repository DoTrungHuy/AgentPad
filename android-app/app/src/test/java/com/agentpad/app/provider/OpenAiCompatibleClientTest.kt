package com.agentpad.app.provider

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test
    fun readLimitedReturnsFullBodyUnderCap() {
        val body = "hello-agentpad"
        val result = OpenAiCompatibleClient.readLimited(
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
            maxBytes = 1024
        )
        assertEquals(body, result)
    }

    @Test
    fun readLimitedRejectsOversizedResponse() {
        val body = "x".repeat(100)
        val error = assertThrows(ProviderException::class.java) {
            OpenAiCompatibleClient.readLimited(
                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
                maxBytes = 50
            )
        }
        assertTrue(error.message.orEmpty().contains("上限"))
    }

    @Test
    fun readLimitedTreatsNullStreamAsEmpty() {
        assertEquals("", OpenAiCompatibleClient.readLimited(null, maxBytes = 10))
    }
}
