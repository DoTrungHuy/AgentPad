package com.agentpad.app.domain

import java.util.UUID

data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val templateId: String,
    val endpoint: String,
    val model: String
) {
    fun toProviderSettings(): ProviderSettings = ProviderSettings(
        providerId = templateId,
        endpoint = endpoint,
        model = model
    )
}

data class ProviderProfileState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String? = null
) {
    val activeProfile: ProviderProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}
