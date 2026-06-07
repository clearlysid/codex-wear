package com.sidekick.watch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sidekick.watch.BuildConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sidekick_settings")

private val json = Json { ignoreUnknownKeys = true }

data class AgentSettings(
    val backendId: String = AgentBackends.hermes.id,
    val baseUrl: String = AgentBackends.hermes.defaultBaseUrl,
    val authToken: String = BuildConfig.DEFAULT_AUTH_TOKEN,
    val model: String = AgentBackends.hermes.defaultModel.orEmpty(),
    val voiceInputProviderId: String = VoiceInputProviders.SARVAM,
    val sttBaseUrl: String = VoiceInputProviders.SARVAM_BASE_URL,
    val sttAuthToken: String = BuildConfig.DEFAULT_STT_AUTH_TOKEN,
    val sttModel: String = "saaras:v3",
    val sttLanguageCode: String = "unknown",
    val sttMode: String = "transcribe",
)

object VoiceInputProviders {
    const val SARVAM = "sarvam"
    const val ANDROID_RECOGNIZER = "android_recognizer"
    const val SARVAM_BASE_URL = "wss://api.sarvam.ai"
}

class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<AgentSettings> =
        context.dataStore.data
            .catch { ex ->
                if (ex is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw ex
                }
            }
            .map { prefs ->
                val backend = AgentBackends.fromId(prefs[BACKEND_ID_KEY])
                AgentSettings(
                    backendId = backend.id,
                    baseUrl = prefs[BASE_URL_KEY]?.ifBlank { backend.defaultBaseUrl } ?: backend.defaultBaseUrl,
                    authToken = prefs[AUTH_TOKEN_KEY]?.ifBlank { BuildConfig.DEFAULT_AUTH_TOKEN } ?: BuildConfig.DEFAULT_AUTH_TOKEN,
                    model = prefs[MODEL_KEY]?.ifBlank { backend.defaultModel.orEmpty() } ?: backend.defaultModel.orEmpty(),
                    voiceInputProviderId = prefs[VOICE_INPUT_PROVIDER_KEY] ?: VoiceInputProviders.SARVAM,
                    sttBaseUrl = prefs[STT_BASE_URL_KEY]?.ifBlank { VoiceInputProviders.SARVAM_BASE_URL } ?: VoiceInputProviders.SARVAM_BASE_URL,
                    sttAuthToken = prefs[STT_AUTH_TOKEN_KEY]?.ifBlank { BuildConfig.DEFAULT_STT_AUTH_TOKEN } ?: BuildConfig.DEFAULT_STT_AUTH_TOKEN,
                    sttModel = prefs[STT_MODEL_KEY]?.ifBlank { "saaras:v3" } ?: "saaras:v3",
                    sttLanguageCode = prefs[STT_LANGUAGE_CODE_KEY]?.ifBlank { "unknown" } ?: "unknown",
                    sttMode = prefs[STT_MODE_KEY]?.ifBlank { "transcribe" } ?: "transcribe",
                )
            }

    suspend fun saveSettings(backendId: String, baseUrl: String, authToken: String, model: String) {
        context.dataStore.edit { prefs ->
            val backend = AgentBackends.fromId(backendId)
            val normalizedBaseUrl = normalizeBaseUrl(baseUrl).ifBlank { backend.defaultBaseUrl }
            prefs[BACKEND_ID_KEY] = backend.id
            prefs[BASE_URL_KEY] = normalizedBaseUrl
            prefs[AUTH_TOKEN_KEY] = authToken.trim()
            prefs[MODEL_KEY] = model.trim().ifBlank { backend.defaultModel.orEmpty() }
        }
    }

    suspend fun saveVoiceSettings(
        providerId: String,
        sttBaseUrl: String,
        sttAuthToken: String,
        sttModel: String,
        sttLanguageCode: String,
        sttMode: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_INPUT_PROVIDER_KEY] =
                if (providerId == VoiceInputProviders.ANDROID_RECOGNIZER) {
                    VoiceInputProviders.ANDROID_RECOGNIZER
                } else {
                    VoiceInputProviders.SARVAM
                }
            prefs[STT_BASE_URL_KEY] = normalizeBaseUrl(sttBaseUrl).ifBlank { VoiceInputProviders.SARVAM_BASE_URL }
            prefs[STT_AUTH_TOKEN_KEY] = sttAuthToken.trim()
            prefs[STT_MODEL_KEY] = sttModel.trim().ifBlank { "saaras:v3" }
            prefs[STT_LANGUAGE_CODE_KEY] = sttLanguageCode.trim().ifBlank { "unknown" }
            prefs[STT_MODE_KEY] = sttMode.trim().ifBlank { "transcribe" }
        }
    }

    suspend fun saveConversationState(state: PersistedConversationState) {
        context.dataStore.edit { prefs ->
            prefs[CONVERSATION_STATE_KEY] = json.encodeToString(state)
        }
    }

    suspend fun loadConversationState(): PersistedConversationState? {
        val prefs =
            context.dataStore.data
                .catch { ex ->
                    if (ex is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw ex
                    }
                }
                .first()
        val raw = prefs[CONVERSATION_STATE_KEY].orEmpty()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<PersistedConversationState>(raw) }.getOrNull()
    }

    private companion object {
        val BACKEND_ID_KEY = stringPreferencesKey("backend_id")
        val BASE_URL_KEY = stringPreferencesKey("base_url")
        val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
        val MODEL_KEY = stringPreferencesKey("model")
        val VOICE_INPUT_PROVIDER_KEY = stringPreferencesKey("voice_input_provider")
        val STT_BASE_URL_KEY = stringPreferencesKey("stt_base_url")
        val STT_AUTH_TOKEN_KEY = stringPreferencesKey("stt_auth_token")
        val STT_MODEL_KEY = stringPreferencesKey("stt_model")
        val STT_LANGUAGE_CODE_KEY = stringPreferencesKey("stt_language_code")
        val STT_MODE_KEY = stringPreferencesKey("stt_mode")
        val CONVERSATION_STATE_KEY = stringPreferencesKey("conversation_state_json")
    }
}

@Serializable
data class PersistedConversationState(
    val selectedConversationId: String? = null,
    val conversations: List<PersistedConversationSummary> = emptyList(),
    val messagesByConversation: Map<String, List<PersistedChatMessage>> = emptyMap(),
    val backendConversationIds: Map<String, String> = emptyMap(),
)

@Serializable
data class PersistedConversationSummary(
    val id: String,
    val title: String? = null,
    val initialPrompt: String? = null,
    val lastUpdatedEpochMs: Long = 0L,
)

@Serializable
data class PersistedChatMessage(
    val id: String,
    val role: String,
    val text: String,
)
