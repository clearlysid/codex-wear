package com.sidekick.watch.viewmodel

enum class TaskStatusUi {
    IDLE,
    WORKING,
    NEEDS_ATTENTION,
    COMPLETE,
    FAILED,
    STOPPED,
}

data class TaskSummaryUi(
    val id: String,
    val title: String,
    val projectName: String? = null,
    val updatedAtEpochMs: Long,
    val status: TaskStatusUi,
    val unread: Boolean = false,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val activity: List<TaskSummaryUi> = emptyList(),
    val today: List<TaskSummaryUi> = emptyList(),
    val connectionError: String? = null,
)

data class TaskSectionUi(
    val title: String,
    val tasks: List<TaskSummaryUi>,
)

data class AllTasksUiState(
    val isLoading: Boolean = true,
    val recent: List<TaskSummaryUi> = emptyList(),
    val projectSections: List<TaskSectionUi> = emptyList(),
    val connectionError: String? = null,
)

sealed interface TimelineItemUi {
    val id: String

    data class UserMessage(
        override val id: String,
        val text: String,
        val imageUrls: List<String> = emptyList(),
    ) : TimelineItemUi

    data class CodexMessage(
        override val id: String,
        val text: String,
        val isStreaming: Boolean = false,
    ) : TimelineItemUi

    data class ToolActivity(
        override val id: String,
        val title: String,
        val summary: String? = null,
        val isRunning: Boolean = false,
    ) : TimelineItemUi

    data class FileChanges(
        override val id: String,
        val files: List<String>,
        val summary: String? = null,
    ) : TimelineItemUi

    data class Approval(
        override val id: String,
        val prompt: String,
        val detail: String? = null,
        val isResolving: Boolean = false,
        val canRespond: Boolean = true,
    ) : TimelineItemUi

    data class Error(
        override val id: String,
        val message: String,
        val canRetry: Boolean = true,
    ) : TimelineItemUi
}

data class TaskDetailUiState(
    val taskId: String,
    val title: String = "Task",
    val projectName: String? = null,
    val status: TaskStatusUi = TaskStatusUi.IDLE,
    val timeline: List<TimelineItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val connectionError: String? = null,
)

enum class AssistantPhaseUi {
    CONNECTING,
    LISTENING,
    SUBMITTING,
    WAITING,
    STREAMING,
    NEEDS_ATTENTION,
    HANDED_OFF,
    COMPLETE,
    ERROR,
}

data class ProjectOptionUi(
    val id: String?,
    val name: String,
    val cwd: String? = null,
)

data class AssistantUiState(
    val phase: AssistantPhaseUi = AssistantPhaseUi.CONNECTING,
    val transcript: String = "",
    val rmsLevel: Float = 0f,
    val projects: List<ProjectOptionUi> = listOf(ProjectOptionUi(null, "No project")),
    val selectedProjectIndex: Int = 0,
    val responseText: String = "",
    val taskId: String? = null,
    val errorMessage: String? = null,
) {
    val selectedProject: ProjectOptionUi
        get() = projects.getOrElse(selectedProjectIndex) { projects.first() }

    val canRedo: Boolean
        get() = transcript.isNotBlank() && phase == AssistantPhaseUi.LISTENING

    val canSend: Boolean
        get() = transcript.isNotBlank() && phase == AssistantPhaseUi.LISTENING
}
