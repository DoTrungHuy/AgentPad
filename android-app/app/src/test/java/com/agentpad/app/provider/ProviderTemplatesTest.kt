package com.agentpad.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTemplatesTest {
    @Test
    fun acceptsHttpsAndLocalHttpOnly() {
        assertTrue(ProviderTemplates.isEndpointAllowed("https://api.deepseek.com/chat/completions"))
        assertTrue(ProviderTemplates.isEndpointAllowed("http://127.0.0.1:11434/v1/chat/completions"))
        assertTrue(ProviderTemplates.isEndpointAllowed("http://localhost:8080/v1/chat/completions"))
        assertFalse(ProviderTemplates.isEndpointAllowed("http://example.com/v1/chat/completions"))
        assertFalse(ProviderTemplates.isEndpointAllowed("ftp://x"))
        assertFalse(ProviderTemplates.isEndpointAllowed(""))
    }

    @Test
    fun deepseekTemplateHasHttpsEndpoint() {
        val deepseek = ProviderTemplates.byId("deepseek")
        assertTrue(deepseek.endpoint.startsWith("https://"))
        assertTrue(deepseek.defaultModel.isNotBlank())
    }
}
