package com.agentpad.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentpad.app.domain.ProviderProfile
import com.agentpad.app.domain.ProviderProfileState
import com.agentpad.app.domain.ProviderSettings
import com.agentpad.app.provider.ProviderTemplates
import com.agentpad.app.security.SecureApiKeyStore
import com.agentpad.app.security.SecureSecretStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.providerProfileDataStore by preferencesDataStore(name = "agentpad_provider_profiles")

class ProviderProfileStore(
    private val context: Context,
    private val secretStore: SecureSecretStore,
    private val legacyKeyStore: SecureApiKeyStore,
    private val settingsStore: SettingsStore
) {
    private object Keys {
        val profilesJson = stringPreferencesKey("profiles_json")
        val activeId = stringPreferencesKey("active_profile_id")
        val migrated = stringPreferencesKey("migrated_v1")
    }

    val state: Flow<ProviderProfileState> = context.providerProfileDataStore.data.map { values ->
        val profiles = decodeProfiles(values[Keys.profilesJson].orEmpty())
        val active = values[Keys.activeId]
        ProviderProfileState(profiles = profiles, activeProfileId = active)
    }

    suspend fun ensureMigrated() {
        val prefs = context.providerProfileDataStore.data.first()
        if (prefs[Keys.migrated] == "1") return
        val existing = decodeProfiles(prefs[Keys.profilesJson].orEmpty())
        if (existing.isNotEmpty()) {
            context.providerProfileDataStore.edit { it[Keys.migrated] = "1" }
            return
        }
        val legacySettings = settingsStore.preferences.first().providerSettings
        val profile = ProviderProfile(
            id = UUID.randomUUID().toString(),
            displayName = when (legacySettings.providerId) {
                "deepseek" -> "DeepSeek"
                "openai" -> "OpenAI"
                else -> "默认模型"
            },
            templateId = legacySettings.providerId.ifBlank { "deepseek" },
            endpoint = legacySettings.endpoint.ifBlank {
                ProviderTemplates.byId("deepseek").endpoint
            },
            model = legacySettings.model
        )
        context.providerProfileDataStore.edit { values ->
            values[Keys.profilesJson] = encodeProfiles(listOf(profile))
            values[Keys.activeId] = profile.id
            values[Keys.migrated] = "1"
        }
        secretStore.migrateFromLegacyIfNeeded(profile.id, legacyKeyStore)
    }

    suspend fun upsert(profile: ProviderProfile, apiKey: String? = null, makeActive: Boolean = false) {
        require(ProviderTemplates.isEndpointAllowed(profile.endpoint)) {
            "接口必须使用 HTTPS；仅本机回环地址允许 HTTP"
        }
        require(profile.model.isNotBlank()) { "模型名称不能为空" }
        require(profile.displayName.isNotBlank()) { "配置名称不能为空" }
        val current = state.first()
        val nextList = current.profiles
            .filterNot { it.id == profile.id }
            .plus(profile)
        val activeId = when {
            makeActive -> profile.id
            current.activeProfileId != null -> current.activeProfileId
            else -> profile.id
        }
        context.providerProfileDataStore.edit { values ->
            values[Keys.profilesJson] = encodeProfiles(nextList)
            values[Keys.activeId] = activeId ?: profile.id
        }
        if (!apiKey.isNullOrBlank()) {
            secretStore.save(profile.id, apiKey)
        }
        // Keep legacy settings in sync for older code paths.
        if (activeId == profile.id) {
            settingsStore.saveProvider(profile.toProviderSettings())
        }
    }

    suspend fun setActive(profileId: String) {
        val profile = state.first().profiles.firstOrNull { it.id == profileId }
            ?: error("配置不存在")
        context.providerProfileDataStore.edit { it[Keys.activeId] = profileId }
        settingsStore.saveProvider(profile.toProviderSettings())
    }

    suspend fun delete(profileId: String) {
        val current = state.first()
        val next = current.profiles.filterNot { it.id == profileId }
        val nextActive = when {
            current.activeProfileId != profileId -> current.activeProfileId
            else -> next.firstOrNull()?.id
        }
        context.providerProfileDataStore.edit { values ->
            values[Keys.profilesJson] = encodeProfiles(next)
            if (nextActive == null) values.remove(Keys.activeId) else values[Keys.activeId] = nextActive
        }
        secretStore.delete(profileId)
        nextActive?.let { id ->
            next.firstOrNull { it.id == id }?.let { settingsStore.saveProvider(it.toProviderSettings()) }
        }
    }

    fun hasKey(profileId: String): Boolean = secretStore.has(profileId)

    fun readKey(profileId: String): String? = secretStore.read(profileId)

    suspend fun activeSettings(): ProviderSettings =
        state.first().activeProfile?.toProviderSettings()
            ?: ProviderSettings()

    private fun encodeProfiles(profiles: List<ProviderProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("displayName", profile.displayName)
                    .put("templateId", profile.templateId)
                    .put("endpoint", profile.endpoint)
                    .put("model", profile.model)
            )
        }
        return array.toString()
    }

    private fun decodeProfiles(raw: String): List<ProviderProfile> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        ProviderProfile(
                            id = item.getString("id"),
                            displayName = item.getString("displayName"),
                            templateId = item.optString("templateId", "custom"),
                            endpoint = item.getString("endpoint"),
                            model = item.optString("model")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
