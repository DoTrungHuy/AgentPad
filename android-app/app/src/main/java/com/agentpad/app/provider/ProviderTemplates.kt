package com.agentpad.app.provider

import com.agentpad.app.domain.ProviderProfile

data class ProviderTemplate(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val defaultModel: String
)

object ProviderTemplates {
    val ALL: List<ProviderTemplate> = listOf(
        ProviderTemplate(
            id = "deepseek",
            displayName = "DeepSeek",
            endpoint = "https://api.deepseek.com/chat/completions",
            defaultModel = "deepseek-chat"
        ),
        ProviderTemplate(
            id = "openai",
            displayName = "OpenAI",
            endpoint = "https://api.openai.com/v1/chat/completions",
            defaultModel = "gpt-4o-mini"
        ),
        ProviderTemplate(
            id = "custom",
            displayName = "自定义 OpenAI 兼容",
            endpoint = "https://",
            defaultModel = ""
        )
    )

    fun byId(id: String): ProviderTemplate =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == "custom" }

    fun newProfileFromTemplate(templateId: String, displayName: String? = null): ProviderProfile {
        val template = byId(templateId)
        return ProviderProfile(
            displayName = displayName ?: template.displayName,
            templateId = template.id,
            endpoint = template.endpoint,
            model = template.defaultModel
        )
    }

    fun isEndpointAllowed(endpoint: String): Boolean {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = java.net.URI(trimmed)
            val host = uri.host.orEmpty()
            val local = host in setOf("127.0.0.1", "localhost", "::1")
            uri.scheme == "https" || (uri.scheme == "http" && local)
        } catch (_: Exception) {
            false
        }
    }
}
