package com.agentpad.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentpad.app.domain.ProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentPadDataStore by preferencesDataStore(name = "agentpad_settings")

enum class ThemePreference {
    LIGHT,
    DARK
}

data class AppPreferences(
    val providerSettings: ProviderSettings = ProviderSettings(),
    val theme: ThemePreference = ThemePreference.DARK,
    val privacyMode: Boolean = false
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val providerId = stringPreferencesKey("provider_id")
        val endpoint = stringPreferencesKey("endpoint")
        val model = stringPreferencesKey("model")
        val visionEndpoint = stringPreferencesKey("vision_endpoint")
        val visionModel = stringPreferencesKey("vision_model")
        val theme = stringPreferencesKey("theme")
        val privacyMode = booleanPreferencesKey("privacy_mode")
    }

    val preferences: Flow<AppPreferences> = context.agentPadDataStore.data.map { values ->
        AppPreferences(
            providerSettings = ProviderSettings(
                providerId = values[Keys.providerId] ?: "deepseek",
                endpoint = values[Keys.endpoint] ?: "https://api.deepseek.com/chat/completions",
                model = values[Keys.model] ?: "",
                visionEndpoint = values[Keys.visionEndpoint] ?: "",
                visionModel = values[Keys.visionModel] ?: ""
            ),
            theme = runCatching {
                ThemePreference.valueOf(values[Keys.theme] ?: ThemePreference.DARK.name)
            }.getOrDefault(ThemePreference.DARK),
            privacyMode = values[Keys.privacyMode] ?: false
        )
    }

    val providerSettings: Flow<ProviderSettings> = preferences.map { it.providerSettings }

    suspend fun saveProvider(settings: ProviderSettings) {
        context.agentPadDataStore.edit { values ->
            values[Keys.providerId] = settings.providerId.trim()
            values[Keys.endpoint] = settings.endpoint.trim()
            values[Keys.model] = settings.model.trim()
            values[Keys.visionEndpoint] = settings.visionEndpoint.trim()
            values[Keys.visionModel] = settings.visionModel.trim()
        }
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.agentPadDataStore.edit { it[Keys.theme] = theme.name }
    }

    suspend fun setPrivacyMode(enabled: Boolean) {
        context.agentPadDataStore.edit { it[Keys.privacyMode] = enabled }
    }

    suspend fun clear() {
        context.agentPadDataStore.edit { it.clear() }
    }
}
