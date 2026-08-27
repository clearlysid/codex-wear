package com.codex.wear.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.wear.data.codex.CodexApprovalDecision
import com.codex.wear.data.codex.CodexPendingAction
import com.codex.wear.data.codex.CodexTaskRepository
import com.codex.wear.data.codex.CodexTaskState
import com.codex.wear.data.codex.CodexTimelineItem
import com.codex.wear.service.CodexMonitorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val appContext: Context,
    private val repository: CodexTaskRepository,
    private val replyToTaskId: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    private var graceJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.state.first { it.isCacheLoaded }
            val frozenProjects =
                listOf(ProjectOptionUi(id = null, name = "No project")) +
                    repository.recentProjects(5).map { project ->
                        ProjectOptionUi(project.id, project.name, project.cwd)
                    }
            _uiState.value = _uiState.value.copy(projects = frozenProjects, selectedProjectIndex = 0)
        }
        viewModelScope.launch {
            repository.state.collect { state ->
                val taskId = _uiState.value.taskId ?: return@collect
                val summary = state.tasks.firstOrNull { it.id == taskId } ?: return@collect
                val detail = state.details[taskId]
                val response =
                    detail?.turns
                        ?.flatMap { it.items }
                        ?.filterIsInstance<CodexTimelineItem.AgentMessage>()
                        ?.lastOrNull()
                        ?.text
                        .orEmpty()
                val hasToolWork =
                    detail?.turns?.flatMap { it.items }?.any {
                        it is CodexTimelineItem.CommandExecution ||
                            it is CodexTimelineItem.ToolCall ||
                            it is CodexTimelineItem.FileChanges
                    } == true
                val pending = detail?.pendingActions?.firstOrNull()
                val nextPhase =
                    when {
                        pending != null -> AssistantPhaseUi.NEEDS_ATTENTION
                        summary.state == CodexTaskState.ERROR -> AssistantPhaseUi.ERROR
                        summary.state == CodexTaskState.COMPLETE && response.isNotBlank() -> AssistantPhaseUi.COMPLETE
                        summary.state == CodexTaskState.COMPLETE -> AssistantPhaseUi.COMPLETE
                        hasToolWork && response.isBlank() -> AssistantPhaseUi.HANDED_OFF
                        response.isNotBlank() -> AssistantPhaseUi.STREAMING
                        _uiState.value.phase in setOf(AssistantPhaseUi.SUBMITTING, AssistantPhaseUi.WAITING) ->
                            AssistantPhaseUi.WAITING
                        else -> _uiState.value.phase
                    }
                _uiState.value =
                    _uiState.value.copy(
                        phase = nextPhase,
                        responseText = response,
                        errorMessage = summary.errorMessage,
                    )
                if (nextPhase == AssistantPhaseUi.COMPLETE && response.isNotBlank()) {
                    repository.markCompletionShown(taskId)
                    graceJob?.cancel()
                    refreshJob?.cancel()
                }
            }
        }
    }

    fun listening() {
        if (_uiState.value.phase == AssistantPhaseUi.CONNECTING) {
            _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.LISTENING)
        }
    }

    fun updateTranscript(text: String) {
        if (_uiState.value.phase == AssistantPhaseUi.LISTENING || _uiState.value.phase == AssistantPhaseUi.CONNECTING) {
            _uiState.value = _uiState.value.copy(transcript = text)
        }
    }

    fun updateLevel(level: Float) {
        _uiState.value = _uiState.value.copy(rmsLevel = level)
    }

    fun fail(message: String) {
        _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.ERROR, errorMessage = message)
    }

    fun projectStep(direction: Int) {
        val current = _uiState.value
        if (current.phase != AssistantPhaseUi.LISTENING || current.projects.isEmpty()) return
        val next = (current.selectedProjectIndex + direction).coerceIn(0, current.projects.lastIndex)
        if (next != current.selectedProjectIndex) _uiState.value = current.copy(selectedProjectIndex = next)
    }

    fun redo() {
        if (_uiState.value.phase !in setOf(AssistantPhaseUi.LISTENING, AssistantPhaseUi.ERROR)) return
        _uiState.value =
            _uiState.value.copy(
                phase = AssistantPhaseUi.CONNECTING,
                transcript = "",
                rmsLevel = 0f,
                errorMessage = null,
            )
    }

    fun submit() {
        val current = _uiState.value
        val transcript = current.transcript.trim()
        if (!current.canSend) return
        _uiState.value = current.copy(phase = AssistantPhaseUi.SUBMITTING, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                if (replyToTaskId.isNullOrBlank()) {
                    val option = current.selectedProject
                    val project = repository.recentProjects(5).firstOrNull { it.id == option.id }
                    repository.startTask(transcript, project)
                } else {
                    repository.followUp(replyToTaskId, transcript)
                }
            }.onSuccess { task ->
                _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.WAITING, taskId = task.id)
                CodexMonitorService.start(appContext)
                startGraceWindow(task.id)
            }.onFailure { error ->
                _uiState.value =
                    _uiState.value.copy(
                        phase = AssistantPhaseUi.ERROR,
                        errorMessage = error.message ?: "Couldn’t send task",
                    )
            }
        }
    }

    fun approve() = decide(CodexApprovalDecision.ACCEPT)

    fun decline() = decide(CodexApprovalDecision.DECLINE)

    private fun decide(decision: CodexApprovalDecision) {
        val taskId = _uiState.value.taskId ?: return
        val action: CodexPendingAction =
            repository.state.value.details[taskId]?.pendingActions?.firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { repository.decide(action, decision) }
                .onSuccess { _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.WAITING) }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.ERROR, errorMessage = error.message)
                }
        }
    }

    fun retry() {
        val taskId = _uiState.value.taskId
        if (taskId == null) {
            _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.LISTENING, errorMessage = null)
        } else {
            _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.WAITING, errorMessage = null)
            repository.loadTaskAsync(taskId)
        }
    }

    private fun startGraceWindow(taskId: String) {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                repeat(HARD_GRACE_SECONDS.toInt()) {
                    runCatching { repository.loadTask(taskId) }
                    delay(1_000L)
                }
            }
        graceJob?.cancel()
        graceJob =
            viewModelScope.launch {
                delay(SOFT_GRACE_MS)
                if (_uiState.value.taskId != taskId) return@launch
                if (_uiState.value.responseText.isBlank() && _uiState.value.phase == AssistantPhaseUi.WAITING) {
                    _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.HANDED_OFF)
                    refreshJob?.cancel()
                    return@launch
                }
                delay(HARD_GRACE_MS - SOFT_GRACE_MS)
                if (_uiState.value.phase in setOf(AssistantPhaseUi.WAITING, AssistantPhaseUi.STREAMING)) {
                    _uiState.value = _uiState.value.copy(phase = AssistantPhaseUi.HANDED_OFF)
                    refreshJob?.cancel()
                }
            }
    }

    class Factory(context: Context, private val replyToTaskId: String?) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        private val repository = CodexTaskRepository.get(appContext)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AssistantViewModel(appContext, repository, replyToTaskId) as T
    }

    private companion object {
        const val SOFT_GRACE_MS = 8_000L
        const val HARD_GRACE_MS = 20_000L
        const val HARD_GRACE_SECONDS = 20L
    }
}
