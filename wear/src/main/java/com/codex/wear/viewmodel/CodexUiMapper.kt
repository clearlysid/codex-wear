package com.codex.wear.viewmodel

import com.codex.wear.data.codex.CodexPendingAction
import com.codex.wear.data.codex.CodexTaskDetail
import com.codex.wear.data.codex.CodexTaskState
import com.codex.wear.data.codex.CodexTaskSummary
import com.codex.wear.data.codex.CodexTimelineItem
import com.codex.wear.data.codex.CodexTimelineItemStatus
import com.codex.wear.domain.HomeTaskSections

fun HomeTaskSections.toUi(isLoading: Boolean = false, connectionError: String? = null): HomeUiState =
    HomeUiState(
        isLoading = isLoading,
        activity = activity.map(CodexTaskSummary::toUi),
        today = today.map(CodexTaskSummary::toUi),
        connectionError = connectionError,
    )

fun CodexTaskSummary.toUi(): TaskSummaryUi =
    TaskSummaryUi(
        id = id,
        title = displayTitle,
        projectName = project?.name,
        updatedAtEpochMs = activityAtEpochSeconds * 1_000L,
        status = state.toUi(),
        unread = isUnread,
    )

fun CodexTaskDetail.toUi(isLoading: Boolean = false, connectionError: String? = null): TaskDetailUiState =
    TaskDetailUiState(
        taskId = summary.id,
        title = summary.displayTitle,
        projectName = summary.project?.name,
        status = summary.state.toUi(),
        timeline =
            buildTimelineUi(turns.flatMap { it.items }, pendingActions).let { timeline ->
                if (summary.state == CodexTaskState.NEEDS_ATTENTION && pendingActions.isEmpty()) {
                    timeline +
                        TimelineItemUi.Approval(
                            id = "attention-elsewhere-${summary.id}",
                            prompt = "Codex needs attention",
                            detail = "This request belongs to another connected client.",
                            canRespond = false,
                        )
                } else {
                    timeline
                }
            },
        isLoading = isLoading,
        connectionError = connectionError,
    )

fun CodexTaskState.toUi(): TaskStatusUi =
    when (this) {
        CodexTaskState.IDLE -> TaskStatusUi.IDLE
        CodexTaskState.WORKING -> TaskStatusUi.WORKING
        CodexTaskState.NEEDS_ATTENTION -> TaskStatusUi.NEEDS_ATTENTION
        CodexTaskState.COMPLETE -> TaskStatusUi.COMPLETE
        CodexTaskState.ERROR -> TaskStatusUi.FAILED
    }

private fun buildTimelineUi(
    source: List<CodexTimelineItem>,
    pendingActions: List<CodexPendingAction>,
): List<TimelineItemUi> {
    val pendingByItem = pendingActions.filter { it.itemId != null }.groupBy(CodexPendingAction::itemId)
    val insertedActions = mutableSetOf<CodexPendingAction>()
    val result = mutableListOf<TimelineItemUi>()
    val toolGroup = mutableListOf<CodexTimelineItem>()

    fun flushTools() {
        if (toolGroup.isEmpty()) return
        val first = toolGroup.first()
        val running = toolGroup.any { item ->
            when (item) {
                is CodexTimelineItem.CommandExecution -> item.status == CodexTimelineItemStatus.IN_PROGRESS
                is CodexTimelineItem.ToolCall -> item.status == CodexTimelineItemStatus.IN_PROGRESS
                else -> false
            }
        }
        val labels = toolGroup.map { item ->
            when (item) {
                is CodexTimelineItem.CommandExecution -> item.command.lineSequence().firstOrNull().orEmpty()
                is CodexTimelineItem.ToolCall -> listOfNotNull(item.server, item.tool).joinToString(" · ")
                else -> ""
            }
        }.filter(String::isNotBlank)
        result += TimelineItemUi.ToolActivity(
            id = "tools-${first.id}",
            title = if (toolGroup.size == 1) "Tool activity" else "${toolGroup.size} tool calls",
            summary = labels.take(3).joinToString("\n").takeIf(String::isNotBlank),
            isRunning = running,
        )
        toolGroup.forEach { sourceItem ->
            pendingByItem[sourceItem.id].orEmpty().forEach { action ->
                result += action.toUi()
                insertedActions += action
            }
        }
        toolGroup.clear()
    }

    source.forEach { item ->
        when (item) {
            is CodexTimelineItem.CommandExecution,
            is CodexTimelineItem.ToolCall,
            -> toolGroup += item
            else -> {
                flushTools()
                when (item) {
                    is CodexTimelineItem.UserMessage -> result += TimelineItemUi.UserMessage(
                        id = item.id,
                        text = item.text,
                        imageUrls = item.images.map { it.value },
                    )
                    is CodexTimelineItem.AgentMessage -> result += TimelineItemUi.CodexMessage(
                        id = item.id,
                        text = item.text,
                    )
                    is CodexTimelineItem.FileChanges -> result += TimelineItemUi.FileChanges(
                        id = item.id,
                        files = item.changes.map { it.path },
                    )
                    is CodexTimelineItem.ImageItem -> result += TimelineItemUi.UserMessage(
                        id = item.id,
                        text = "",
                        imageUrls = listOf(item.image.value),
                    )
                    is CodexTimelineItem.Error -> result += TimelineItemUi.Error(
                        id = item.id,
                        message = item.message,
                        canRetry = item.retryable,
                    )
                    is CodexTimelineItem.Unknown -> {
                        if (!item.rawType.contains("reasoning", ignoreCase = true)) {
                            result += TimelineItemUi.ToolActivity(
                                id = item.id,
                                title = item.label ?: item.rawType,
                            )
                        }
                    }
                    is CodexTimelineItem.CommandExecution,
                    is CodexTimelineItem.ToolCall,
                    -> Unit
                }
                pendingByItem[item.id].orEmpty().forEach { action ->
                    result += action.toUi()
                    insertedActions += action
                }
            }
        }
    }
    flushTools()
    pendingActions.filterNot(insertedActions::contains).forEach { result += it.toUi() }
    return result
}

private fun CodexPendingAction.toUi(): TimelineItemUi.Approval =
    TimelineItemUi.Approval(
        id = uiId(),
        prompt = title,
        detail = reason ?: command ?: filePaths.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        canRespond = true,
    )

fun CodexPendingAction.uiId(): String =
    "approval-${handle.connectionGeneration}-${handle.requestId}"
