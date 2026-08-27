package com.sidekick.watch.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidekick.watch.data.AgentBackends
import com.sidekick.watch.data.AgentSettings
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.data.codex.CodexApprovalDecision
import com.sidekick.watch.data.codex.CodexTaskRepository
import com.sidekick.watch.data.codex.CodexTaskState
import com.sidekick.watch.domain.organizeHomeTasks
import com.sidekick.watch.service.CodexMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CodexCompanionUiState(
    val home: HomeUiState = HomeUiState(),
    val taskDetails: Map<String, TaskDetailUiState> = emptyMap(),
    val settings: AgentSettings = AgentSettings(),
)

class CodexCompanionViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val taskRepository: CodexTaskRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CodexCompanionUiState())
    val uiState: StateFlow<CodexCompanionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
        viewModelScope.launch {
            taskRepository.state.collect { repositoryState ->
                if (
                    repositoryState.tasks.any {
                        it.state == CodexTaskState.WORKING || it.state == CodexTaskState.NEEDS_ATTENTION
                    } && !CodexMonitorService.isRunning()
                ) {
                    CodexMonitorService.start(appContext)
                }
                val now = System.currentTimeMillis() / 1_000L
                val error = repositoryState.connectionError
                _uiState.value =
                    _uiState.value.copy(
                        home =
                            organizeHomeTasks(repositoryState.tasks, now)
                                .toUi(repositoryState.isRefreshing, error),
                        taskDetails =
                            repositoryState.details.mapValues { (_, detail) ->
                                detail.toUi(connectionError = error)
                            },
                    )
            }
        }
        taskRepository.refresh()
    }

    fun refresh() = taskRepository.refresh()

    fun openTask(taskId: String) = taskRepository.loadTaskAsync(taskId)

    fun stopTask(taskId: String) {
        viewModelScope.launch { runCatching { taskRepository.interrupt(taskId) } }
    }

    fun approve(taskId: String, approvalUiId: String) {
        decide(taskId, approvalUiId, CodexApprovalDecision.ACCEPT)
    }

    fun decline(taskId: String, approvalUiId: String) {
        decide(taskId, approvalUiId, CodexApprovalDecision.DECLINE)
    }

    fun retryTask(taskId: String) = taskRepository.loadTaskAsync(taskId)

    private fun decide(taskId: String, approvalUiId: String, decision: CodexApprovalDecision) {
        val action = taskRepository.state.value.details[taskId]?.pendingActions
            ?.firstOrNull { it.uiId() == approvalUiId }
            ?: return
        viewModelScope.launch { runCatching { taskRepository.decide(action, decision) } }
    }

    fun saveBaseUrl(value: String) = saveConnection(baseUrl = value)

    fun saveAuthToken(value: String) = saveConnection(authToken = value)

    private fun saveConnection(baseUrl: String? = null, authToken: String? = null) {
        val current = _uiState.value.settings
        viewModelScope.launch {
            settingsRepository.saveSettings(
                backendId = AgentBackends.codex.id,
                baseUrl = baseUrl ?: current.baseUrl,
                authToken = authToken ?: current.authToken,
                model = current.model,
                instructions = current.instructions,
            )
        }
    }

    fun saveVoiceInputProvider(value: String) = saveVoice(providerId = value)

    fun saveSttAuthToken(value: String) = saveVoice(sttAuthToken = value)

    private fun saveVoice(providerId: String? = null, sttAuthToken: String? = null) {
        val current = _uiState.value.settings
        viewModelScope.launch {
            settingsRepository.saveVoiceSettings(
                providerId = providerId ?: current.voiceInputProviderId,
                sttBaseUrl = current.sttBaseUrl,
                sttAuthToken = sttAuthToken ?: current.sttAuthToken,
                sttModel = current.sttModel,
                sttLanguageCode = current.sttLanguageCode,
                sttMode = current.sttMode,
            )
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CodexCompanionViewModel(
                appContext = appContext,
                settingsRepository = SettingsRepository(appContext),
                taskRepository = CodexTaskRepository.get(appContext),
            ) as T
    }
}
