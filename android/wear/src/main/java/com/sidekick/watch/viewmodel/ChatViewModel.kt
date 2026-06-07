package com.sidekick.watch.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidekick.watch.BuildConfig
import com.sidekick.watch.data.AgentBackends
import com.sidekick.watch.data.AgentRequestBus
import com.sidekick.watch.data.AgentSettings
import com.sidekick.watch.data.PersistedChatMessage
import com.sidekick.watch.data.PersistedConversationState
import com.sidekick.watch.data.PersistedConversationSummary
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.data.VoiceInputProviders
import com.sidekick.watch.service.AgentService
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ChatViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val logTag = "SidekickInput"

    private val _uiState =
        MutableStateFlow(
            ChatUiState(
                conversations = emptyList(),
                selectedConversationId = null,
                messagesByConversation = emptyMap(),
                backendConversationIds = emptyMap(),
            ),
        )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val senderId = "wear-user"
    private var requestTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        savedSettings = settings,
                        agentFlavorInput = if (it.agentFlavorInput.isEmpty()) settings.backendId else it.agentFlavorInput,
                        baseUrlInput = if (it.baseUrlInput.isEmpty()) settings.baseUrl else it.baseUrlInput,
                        authTokenInput = if (it.authTokenInput.isEmpty()) settings.authToken else it.authTokenInput,
                        modelInput = if (it.modelInput.isEmpty()) settings.model else it.modelInput,
                        voiceInputProviderId = settings.voiceInputProviderId,
                        sttBaseUrlInput = if (it.sttBaseUrlInput.isEmpty()) settings.sttBaseUrl else it.sttBaseUrlInput,
                        sttAuthTokenInput = if (it.sttAuthTokenInput.isEmpty()) settings.sttAuthToken else it.sttAuthTokenInput,
                        sttModelInput = if (it.sttModelInput.isEmpty()) settings.sttModel else it.sttModelInput,
                        sttLanguageCodeInput = if (it.sttLanguageCodeInput.isEmpty()) settings.sttLanguageCode else it.sttLanguageCodeInput,
                        sttModeInput = if (it.sttModeInput.isEmpty()) settings.sttMode else it.sttModeInput,
                    )
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.themeFlow.collect { themeId ->
                _uiState.update { it.copy(themeId = themeId) }
            }
        }

        viewModelScope.launch {
            // Load persisted state BEFORE observing the bus to avoid overwriting
            // DataStore state with empty in-memory state.
            val persisted = settingsRepository.loadConversationState()
            if (persisted != null) {
                _uiState.update { state ->
                    state.copy(
                        conversations = persisted.conversations.map { it.toConversationSummary() },
                        selectedConversationId = persisted.selectedConversationId ?: persisted.conversations.firstOrNull()?.id,
                        messagesByConversation = persisted.messagesByConversation.mapValues { (_, messages) -> messages.mapNotNull { it.toChatMessageOrNull() } },
                        backendConversationIds = persisted.backendConversationIds,
                    )
                }
            }
            _uiState.update { it.copy(isConversationStateLoaded = true) }

            AgentRequestBus.state.collect { requestState ->
                val convId = requestState.conversationId ?: return@collect
                _uiState.update { state ->
                    when {
                        requestState.error != null -> state.copy(
                            isPolling = false,
                            isSending = false,
                            errorMessage = requestState.error,
                        )
                        requestState.finalText != null -> {
                            val existing = state.messagesByConversation[convId].orEmpty()
                            val withoutStreaming = existing.filter { it.id != STREAMING_MESSAGE_ID }
                            val botMsg = ChatMessage(role = MessageRole.BOT, text = requestState.finalText)
                            state.copy(
                                messagesByConversation = state.messagesByConversation + (convId to (withoutStreaming + botMsg)),
                                conversations = updateConversationMeta(state.conversations, convId, requestState.finalText, false),
                                isPolling = false,
                                isSending = false,
                            )
                        }
                        requestState.isActive && requestState.streamingText.isNotEmpty() -> {
                            val existing = state.messagesByConversation[convId].orEmpty()
                            val withoutStreaming = existing.filter { it.id != STREAMING_MESSAGE_ID }
                            val streamingMsg = ChatMessage(id = STREAMING_MESSAGE_ID, role = MessageRole.BOT, text = requestState.streamingText)
                            state.copy(
                                messagesByConversation = state.messagesByConversation + (convId to (withoutStreaming + streamingMsg)),
                            )
                        }
                        else -> state
                    }
                }
                if (requestState.finalText != null || requestState.error != null) {
                    requestTimeoutJob?.cancel()
                    requestTimeoutJob = null
                    persistConversationState()
                    AgentRequestBus.reset()
                }
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        _uiState.update { state ->
            val updated = state.conversations.filter { it.id != conversationId }
            val newSelected = if (state.selectedConversationId == conversationId) {
                updated.firstOrNull()?.id
            } else {
                state.selectedConversationId
            }
            state.copy(
                conversations = updated,
                selectedConversationId = newSelected,
                messagesByConversation = state.messagesByConversation - conversationId,
                backendConversationIds = state.backendConversationIds - conversationId,
            )
        }
        persistConversationState()
    }

    fun resetAll() {
        _uiState.update {
            ChatUiState(isConversationStateLoaded = true)
        }
        viewModelScope.launch {
            settingsRepository.saveSettings(
                backendId = AgentBackends.openclaw.id,
                baseUrl = AgentBackends.openclaw.defaultBaseUrl,
                authToken = BuildConfig.DEFAULT_AUTH_TOKEN,
                model = AgentBackends.openclaw.defaultModel.orEmpty(),
            )
            settingsRepository.saveVoiceSettings(
                providerId = VoiceInputProviders.SARVAM,
                sttBaseUrl = VoiceInputProviders.SARVAM_BASE_URL,
                sttAuthToken = BuildConfig.DEFAULT_STT_AUTH_TOKEN,
                sttModel = "saaras:v3",
                sttLanguageCode = "unknown",
                sttMode = "transcribe",
            )
            settingsRepository.saveConversationState(
                PersistedConversationState(),
            )
            settingsRepository.saveTheme("default")
        }
    }

    fun saveTheme(themeId: String) {
        _uiState.update { it.copy(themeId = themeId) }
        viewModelScope.launch { settingsRepository.saveTheme(themeId) }
    }

    fun openConversation(conversationId: String) {
        _uiState.update { state ->
            if (state.conversations.none { it.id == conversationId }) {
                state
            } else {
                state.copy(
                    selectedConversationId = conversationId,
                    errorMessage = null,
                )
            }
        }
        persistConversationState()
    }

    fun startNewConversation(): String {
        val newConversation =
            ConversationSummary(
                id = "conversation-${UUID.randomUUID().toString().take(8)}",
                initialPrompt = null,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            )

        _uiState.update { state ->
            state.copy(
                conversations = listOf(newConversation) + state.conversations,
                selectedConversationId = newConversation.id,
                messagesByConversation = state.messagesByConversation + (newConversation.id to emptyList()),
                backendConversationIds = state.backendConversationIds + (newConversation.id to UUID.randomUUID().toString()),
                errorMessage = null,
            )
        }
        persistConversationState()

        return newConversation.id
    }

    fun saveAgentFlavor(backendId: String) {
        _uiState.update { state ->
            val currentBackend = AgentBackends.fromId(state.agentFlavorInput)
            val nextBackend = AgentBackends.fromId(backendId)
            val normalizedBaseUrl = state.baseUrlInput.trim().trimEnd('/')
            val nextBaseUrl =
                when {
                    state.baseUrlInput.isBlank() -> nextBackend.defaultBaseUrl
                    normalizedBaseUrl == currentBackend.defaultBaseUrl -> nextBackend.defaultBaseUrl
                    else -> state.baseUrlInput
                }
            val nextModel =
                when {
                    state.modelInput.isBlank() -> nextBackend.defaultModel.orEmpty()
                    state.modelInput.trim() == currentBackend.defaultModel.orEmpty() -> nextBackend.defaultModel.orEmpty()
                    else -> state.modelInput
                }

            state.copy(
                agentFlavorInput = nextBackend.id,
                baseUrlInput = nextBaseUrl,
                modelInput = nextModel,
            )
        }
        persistCurrentSettings()
    }

    fun saveBaseUrl(baseUrl: String) {
        _uiState.update { it.copy(baseUrlInput = baseUrl) }
        persistCurrentSettings()
    }

    fun saveAuthToken(authToken: String) {
        _uiState.update { it.copy(authTokenInput = authToken) }
        persistCurrentSettings()
    }

    fun saveModel(model: String) {
        _uiState.update { it.copy(modelInput = model) }
        persistCurrentSettings()
    }

    fun saveVoiceInputProvider(providerId: String) {
        _uiState.update { it.copy(voiceInputProviderId = providerId) }
        persistCurrentVoiceSettings()
    }

    fun saveSttBaseUrl(baseUrl: String) {
        _uiState.update { it.copy(sttBaseUrlInput = baseUrl) }
        persistCurrentVoiceSettings()
    }

    fun saveSttAuthToken(authToken: String) {
        _uiState.update { it.copy(sttAuthTokenInput = authToken) }
        persistCurrentVoiceSettings()
    }

    fun saveSttModel(model: String) {
        _uiState.update { it.copy(sttModelInput = model) }
        persistCurrentVoiceSettings()
    }

    fun saveSttLanguageCode(languageCode: String) {
        _uiState.update { it.copy(sttLanguageCodeInput = languageCode) }
        persistCurrentVoiceSettings()
    }

    fun saveSttMode(mode: String) {
        _uiState.update { it.copy(sttModeInput = mode) }
        persistCurrentVoiceSettings()
    }

    private fun persistCurrentSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.saveSettings(
                backendId = state.agentFlavorInput,
                baseUrl = state.baseUrlInput,
                authToken = state.authTokenInput,
                model = state.modelInput,
            )
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun persistCurrentVoiceSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.saveVoiceSettings(
                providerId = state.voiceInputProviderId,
                sttBaseUrl = state.sttBaseUrlInput,
                sttAuthToken = state.sttAuthTokenInput,
                sttModel = state.sttModelInput,
                sttLanguageCode = state.sttLanguageCodeInput,
                sttMode = state.sttModeInput,
            )
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun sendMessage(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false

        val state = _uiState.value
        if (state.isSending || state.isPolling) {
            _uiState.update { it.copy(errorMessage = "Agent busy") }
            return false
        }

        val localConversationId = state.selectedConversationId
        if (localConversationId == null) {
            _uiState.update { it.copy(errorMessage = "Choose a conversation first.") }
            return false
        }

        val settings = state.savedSettings
        val backend = AgentBackends.fromId(settings.backendId)
        Log.i(logTag, "Using backend=${backend.id} baseUrl=${settings.baseUrl}")
        if (settings.baseUrl.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Set ${backend.displayName} URL in Settings first.")
            }
            return false
        }

        val backendConversationId = state.backendConversationIds[localConversationId] ?: UUID.randomUUID().toString()
        if (!state.backendConversationIds.containsKey(localConversationId)) {
            _uiState.update {
                it.copy(backendConversationIds = it.backendConversationIds + (localConversationId to backendConversationId))
            }
        }

        val userMessage = ChatMessage(role = MessageRole.USER, text = trimmed)
        _uiState.update {
            val existingMessages = it.messagesByConversation[localConversationId].orEmpty()
            it.copy(
                messagesByConversation = it.messagesByConversation + (localConversationId to (existingMessages + userMessage)),
                conversations =
                    updateConversationMeta(
                        conversations = it.conversations,
                        conversationId = localConversationId,
                        inputText = trimmed,
                        allowInitialPromptUpdate = true,
                    ),
                isSending = true,
                errorMessage = null,
            )
        }
        persistConversationState()

        when (backend.id) {
            "openclaw" -> sendViaOpenAI(localConversationId, backendConversationId, settings)
            else -> sendViaSpacebot(localConversationId, backendConversationId, trimmed, settings)
        }
        scheduleRequestTimeout()
        return true
    }

    private fun scheduleRequestTimeout() {
        requestTimeoutJob?.cancel()
        requestTimeoutJob = viewModelScope.launch {
            delay(REQUEST_TIMEOUT_MS)
            _uiState.update {
                it.copy(
                    isSending = false,
                    isPolling = false,
                    errorMessage = "Agent timed out",
                )
            }
            AgentRequestBus.reset()
            persistConversationState()
        }
    }

    private fun sendViaSpacebot(
        localConversationId: String,
        backendConversationId: String,
        content: String,
        settings: AgentSettings,
    ) {
        _uiState.update { it.copy(isSending = false, isPolling = true) }
        AgentService.startSpacebot(
            context = context,
            conversationId = localConversationId,
            backendConversationId = backendConversationId,
            settings = settings,
            content = content,
            senderId = senderId,
        )
    }

    private fun sendViaOpenAI(localConversationId: String, backendConversationId: String, settings: AgentSettings) {
        _uiState.update { it.copy(isSending = false, isPolling = true) }

        val history = _uiState.value.messagesByConversation[localConversationId].orEmpty()
        val messagesJson = serializeMessages(history)

        AgentService.startOpenAI(
            context = context,
            conversationId = localConversationId,
            backendConversationId = backendConversationId,
            settings = settings,
            messagesJson = messagesJson,
        )
    }

    private fun serializeMessages(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { msg ->
            array.put(
                JSONObject()
                    .put("role", when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.BOT -> "assistant"
                    })
                    .put("content", msg.text)
            )
        }
        return array.toString()
    }

    private fun persistConversationState() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.saveConversationState(state.toPersistedConversationState())
        }
    }

    private fun updateConversationMeta(
        conversations: List<ConversationSummary>,
        conversationId: String,
        inputText: String,
        allowInitialPromptUpdate: Boolean,
    ): List<ConversationSummary> {
        val normalized = inputText.trim().replace("\n", " ")
        val now = System.currentTimeMillis()
        return conversations.map { conversation ->
            if (conversation.id != conversationId) {
                conversation
            } else {
                val nextPrompt =
                    if (allowInitialPromptUpdate && conversation.initialPrompt.isNullOrBlank()) normalized
                    else conversation.initialPrompt
                conversation.copy(
                    initialPrompt = nextPrompt,
                    lastUpdatedEpochMs = now,
                )
            }
        }
    }

    class Factory(
        private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(context, settingsRepository) as T
        }
    }

    companion object {
        private const val STREAMING_MESSAGE_ID = "__streaming__"
        private const val REQUEST_TIMEOUT_MS = 90_000L
    }
}

private fun ChatUiState.toPersistedConversationState(): PersistedConversationState =
    PersistedConversationState(
        selectedConversationId = selectedConversationId,
        conversations = conversations.map { it.toPersistedConversationSummary() },
        messagesByConversation =
            messagesByConversation.mapValues { (_, messages) ->
                messages.map { it.toPersistedChatMessage() }
            },
        backendConversationIds = backendConversationIds,
    )

private fun ConversationSummary.toPersistedConversationSummary(): PersistedConversationSummary =
    PersistedConversationSummary(
        id = id,
        initialPrompt = initialPrompt,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
    )

private fun PersistedConversationSummary.toConversationSummary(): ConversationSummary =
    ConversationSummary(
        id = id,
        initialPrompt = initialPrompt,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
    )

private fun ChatMessage.toPersistedChatMessage(): PersistedChatMessage =
    PersistedChatMessage(
        id = id,
        role = role.name,
        text = text,
    )

private fun PersistedChatMessage.toChatMessageOrNull(): ChatMessage? {
    val parsedRole = MessageRole.entries.firstOrNull { it.name == role } ?: return null
    return ChatMessage(
        id = id,
        role = parsedRole,
        text = text,
    )
}

data class ChatUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val selectedConversationId: String? = null,
    val messagesByConversation: Map<String, List<ChatMessage>> = emptyMap(),
    val backendConversationIds: Map<String, String> = emptyMap(),
    val savedSettings: AgentSettings = AgentSettings(),
    val agentFlavorInput: String = "",
    val baseUrlInput: String = "",
    val authTokenInput: String = "",
    val modelInput: String = "",
    val voiceInputProviderId: String = "",
    val sttBaseUrlInput: String = "",
    val sttAuthTokenInput: String = "",
    val sttModelInput: String = "",
    val sttLanguageCodeInput: String = "",
    val sttModeInput: String = "",
    val isSending: Boolean = false,
    val isPolling: Boolean = false,
    val errorMessage: String? = null,
    val themeId: String = "default",
    val isConversationStateLoaded: Boolean = false,
) {
    val selectedAgentFlavorName: String
        get() = AgentBackends.fromId(agentFlavorInput).displayName

    val activeAgentName: String
        get() = AgentBackends.fromId(savedSettings.backendId).displayName

    val currentConversation: ConversationSummary?
        get() = conversations.firstOrNull { it.id == selectedConversationId }

    val currentConversationTitle: String
        get() = currentConversation?.initialPrompt?.take(40)?.ifBlank { "New conversation" } ?: "Conversation"

    val messages: List<ChatMessage>
        get() = selectedConversationId?.let { messagesByConversation[it].orEmpty() }.orEmpty()
}

data class ConversationSummary(
    val id: String,
    val initialPrompt: String?,
    val lastUpdatedEpochMs: Long,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
)

enum class MessageRole {
    USER,
    BOT,
}
