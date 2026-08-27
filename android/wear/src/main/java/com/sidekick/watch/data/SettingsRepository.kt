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
    val backendId: String = AgentBackends.codex.id,
    val baseUrl: String = AgentBackends.codex.defaultBaseUrl,
    val authToken: String = BuildConfig.DEFAULT_CODEX_AUTH_TOKEN,
    val model: String = AgentBackends.codex.defaultModel.orEmpty(),
    val instructions: String = DEFAULT_WATCH_INSTRUCTIONS,
    val voiceInputProviderId: String = VoiceInputProviders.SARVAM,
    val sttBaseUrl: String = VoiceInputProviders.SARVAM_BASE_URL,
    val sttAuthToken: String = BuildConfig.DEFAULT_STT_AUTH_TOKEN,
    val sttModel: String = "saaras:v3",
    val sttLanguageCode: String = "unknown",
    val sttMode: String = "transcribe",
)

const val DEFAULT_WATCH_INSTRUCTIONS =
    "You are being addressed from a watch. Keep answers short and crisp. Do not use headings, long paragraphs, or complex formatting."

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
                settingsFromPreferences(
                    prefs = prefs,
                    backend = AgentBackends.codex,
                    allowLegacy = prefs[BACKEND_ID_KEY] == AgentBackends.codex.id,
                )
            }

    suspend fun saveSettings(
        backendId: String,
        baseUrl: String,
        authToken: String,
        model: String,
        instructions: String,
    ) {
        context.dataStore.edit { prefs ->
            val backend = AgentBackends.codex
            val normalizedBaseUrl = normalizeBaseUrl(baseUrl).ifBlank { backend.defaultBaseUrl }
            prefs[BACKEND_ID_KEY] = backend.id
            prefs[BASE_URL_KEY] = normalizedBaseUrl
            prefs[AUTH_TOKEN_KEY] = authToken.trim()
            prefs[MODEL_KEY] = model.trim().ifBlank { backend.defaultModel.orEmpty() }
            prefs[INSTRUCTIONS_KEY] = instructions.trim()
            prefs[backendBaseUrlKey(backend.id)] = normalizedBaseUrl
            prefs[backendAuthTokenKey(backend.id)] = authToken.trim()
            prefs[backendModelKey(backend.id)] = model.trim().ifBlank { backend.defaultModel.orEmpty() }
            prefs[backendInstructionsKey(backend.id)] = instructions.trim()
        }
    }

    suspend fun loadBackendSettings(backendId: String): AgentSettings {
        val prefs =
            context.dataStore.data
                .catch { ex ->
                    if (ex is IOException) emit(emptyPreferences()) else throw ex
                }
                .first()
        val backend = AgentBackends.codex
        return settingsFromPreferences(
            prefs = prefs,
            backend = backend,
            allowLegacy = prefs[BACKEND_ID_KEY] == backend.id,
        )
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

    private fun settingsFromPreferences(
        prefs: Preferences,
        backend: AgentBackend,
        allowLegacy: Boolean,
    ): AgentSettings {
        val baseUrl =
            prefs[backendBaseUrlKey(backend.id)]
                ?: if (allowLegacy) prefs[BASE_URL_KEY] else null
        val authToken =
            prefs[backendAuthTokenKey(backend.id)]
                ?: if (allowLegacy) prefs[AUTH_TOKEN_KEY] else null
        val model =
            prefs[backendModelKey(backend.id)]
                ?: if (allowLegacy) prefs[MODEL_KEY] else null
        val instructions =
            prefs[backendInstructionsKey(backend.id)]
                ?: if (allowLegacy) prefs[INSTRUCTIONS_KEY] else null
        val defaultAuthToken = BuildConfig.DEFAULT_CODEX_AUTH_TOKEN
        val resolvedBaseUrl =
            baseUrl
                ?.takeUnless { it.isBlank() || normalizeBaseUrl(it) == LEGACY_CODEX_BASE_URL }
                ?: backend.defaultBaseUrl
        return AgentSettings(
            backendId = backend.id,
            baseUrl = resolvedBaseUrl,
            authToken = authToken ?: defaultAuthToken,
            model = model?.ifBlank { backend.defaultModel.orEmpty() } ?: backend.defaultModel.orEmpty(),
            instructions =
                instructions
                    ?: if (backend.protocol == AgentBackendProtocol.CODEX_APP_SERVER) DEFAULT_WATCH_INSTRUCTIONS else "",
            voiceInputProviderId = prefs[VOICE_INPUT_PROVIDER_KEY] ?: VoiceInputProviders.SARVAM,
            sttBaseUrl = prefs[STT_BASE_URL_KEY]?.ifBlank { VoiceInputProviders.SARVAM_BASE_URL } ?: VoiceInputProviders.SARVAM_BASE_URL,
            sttAuthToken = prefs[STT_AUTH_TOKEN_KEY]?.ifBlank { BuildConfig.DEFAULT_STT_AUTH_TOKEN } ?: BuildConfig.DEFAULT_STT_AUTH_TOKEN,
            sttModel = prefs[STT_MODEL_KEY]?.ifBlank { "saaras:v3" } ?: "saaras:v3",
            sttLanguageCode = prefs[STT_LANGUAGE_CODE_KEY]?.ifBlank { "unknown" } ?: "unknown",
            sttMode = prefs[STT_MODE_KEY]?.ifBlank { "transcribe" } ?: "transcribe",
        )
    }

    private companion object {
        val BACKEND_ID_KEY = stringPreferencesKey("backend_id")
        val BASE_URL_KEY = stringPreferencesKey("base_url")
        val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
        val MODEL_KEY = stringPreferencesKey("model")
        val INSTRUCTIONS_KEY = stringPreferencesKey("instructions")
        val VOICE_INPUT_PROVIDER_KEY = stringPreferencesKey("voice_input_provider")
        val STT_BASE_URL_KEY = stringPreferencesKey("stt_base_url")
        val STT_AUTH_TOKEN_KEY = stringPreferencesKey("stt_auth_token")
        val STT_MODEL_KEY = stringPreferencesKey("stt_model")
        val STT_LANGUAGE_CODE_KEY = stringPreferencesKey("stt_language_code")
        val STT_MODE_KEY = stringPreferencesKey("stt_mode")
        val CONVERSATION_STATE_KEY = stringPreferencesKey("conversation_state_json")

        const val LEGACY_CODEX_BASE_URL = "wss://donna.catfish-basilisk.ts.net/codex"

        fun backendBaseUrlKey(backendId: String) = stringPreferencesKey("base_url_$backendId")
        fun backendAuthTokenKey(backendId: String) = stringPreferencesKey("auth_token_$backendId")
        fun backendModelKey(backendId: String) = stringPreferencesKey("model_$backendId")
        fun backendInstructionsKey(backendId: String) = stringPreferencesKey("instructions_$backendId")
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
