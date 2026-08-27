package com.sidekick.watch.data.codex

/** A watch-facing state derived from server runtime state plus local read state. */
enum class CodexTaskState {
    IDLE,
    WORKING,
    NEEDS_ATTENTION,
    COMPLETE,
    ERROR,
}

enum class CodexThreadStatusType {
    NOT_LOADED,
    IDLE,
    ACTIVE,
    SYSTEM_ERROR,
    UNKNOWN,
}

/**
 * Runtime state reported by app-server. Unknown flags and types are retained so a newer server
 * does not force the client to discard otherwise useful task data.
 */
data class CodexThreadStatus(
    val type: CodexThreadStatusType,
    val activeFlags: Set<String> = emptySet(),
    val rawType: String? = null,
) {
    val isWaitingOnApproval: Boolean
        get() = WAITING_ON_APPROVAL in activeFlags

    val isWaitingOnUserInput: Boolean
        get() = WAITING_ON_USER_INPUT in activeFlags

    val needsAttention: Boolean
        get() = isWaitingOnApproval || isWaitingOnUserInput

    companion object {
        const val WAITING_ON_APPROVAL = "waitingOnApproval"
        const val WAITING_ON_USER_INPUT = "waitingOnUserInput"

        val NotLoaded = CodexThreadStatus(CodexThreadStatusType.NOT_LOADED)
        val Idle = CodexThreadStatus(CodexThreadStatusType.IDLE)
    }
}

/** A project identity suitable for grouping tasks and selecting a recent project. */
data class CodexProject(
    /** App-server project id when available, otherwise a stable cwd-derived id. */
    val id: String,
    val name: String,
    val cwd: String? = null,
    val lastUsedAtEpochSeconds: Long = 0L,
)

/** Lightweight data used by Home, All Tasks, the Tile, and notification routing. */
data class CodexTaskSummary(
    /** The app-server thread id. */
    val id: String,
    val title: String? = null,
    val preview: String = "",
    val state: CodexTaskState = CodexTaskState.IDLE,
    val threadStatus: CodexThreadStatus = CodexThreadStatus.NotLoaded,
    /** Null represents the explicit No project choice. */
    val project: CodexProject? = null,
    val cwd: String? = null,
    val sourceKind: String? = null,
    /** App-server timestamps are Unix seconds, not milliseconds. */
    val createdAtEpochSeconds: Long = 0L,
    val updatedAtEpochSeconds: Long = 0L,
    val recencyAtEpochSeconds: Long? = null,
    val latestTurnId: String? = null,
    val isUnread: Boolean = false,
    val errorMessage: String? = null,
) {
    val activityAtEpochSeconds: Long
        get() = recencyAtEpochSeconds ?: updatedAtEpochSeconds

    val displayTitle: String
        get() = title?.takeIf(String::isNotBlank)
            ?: preview.takeIf(String::isNotBlank)
            ?: "Untitled task"
}

enum class CodexTurnStatus {
    IN_PROGRESS,
    COMPLETED,
    INTERRUPTED,
    FAILED,
    UNKNOWN,
}

enum class CodexTimelineItemStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    DECLINED,
    UNKNOWN,
}

enum class CodexAgentMessagePhase {
    COMMENTARY,
    FINAL_ANSWER,
    UNKNOWN,
}

enum class CodexImageSource {
    REMOTE_URL,
    SERVER_LOCAL_PATH,
    UNKNOWN,
}

data class CodexImage(
    val value: String,
    val source: CodexImageSource = CodexImageSource.UNKNOWN,
    val altText: String? = null,
)

data class CodexFileChange(
    val path: String,
    val kind: String? = null,
    /** Optional because the watch can render a summary without downloading a full diff. */
    val diff: String? = null,
)

/** Structured app-server items in chronological task order. */
sealed interface CodexTimelineItem {
    val id: String
    val turnId: String?

    data class UserMessage(
        override val id: String,
        override val turnId: String? = null,
        val text: String = "",
        val images: List<CodexImage> = emptyList(),
    ) : CodexTimelineItem

    data class AgentMessage(
        override val id: String,
        override val turnId: String? = null,
        val text: String = "",
        val phase: CodexAgentMessagePhase = CodexAgentMessagePhase.UNKNOWN,
        val rawPhase: String? = null,
    ) : CodexTimelineItem

    data class CommandExecution(
        override val id: String,
        override val turnId: String? = null,
        val command: String,
        val cwd: String? = null,
        val status: CodexTimelineItemStatus = CodexTimelineItemStatus.UNKNOWN,
        val outputPreview: String? = null,
        val exitCode: Int? = null,
        val durationMs: Long? = null,
    ) : CodexTimelineItem

    data class FileChanges(
        override val id: String,
        override val turnId: String? = null,
        val changes: List<CodexFileChange>,
        val status: CodexTimelineItemStatus = CodexTimelineItemStatus.UNKNOWN,
    ) : CodexTimelineItem

    data class ToolCall(
        override val id: String,
        override val turnId: String? = null,
        val server: String? = null,
        val tool: String,
        val status: CodexTimelineItemStatus = CodexTimelineItemStatus.UNKNOWN,
        val argumentsSummary: String? = null,
        val resultSummary: String? = null,
        val errorMessage: String? = null,
    ) : CodexTimelineItem

    data class ImageItem(
        override val id: String,
        override val turnId: String? = null,
        val image: CodexImage,
    ) : CodexTimelineItem

    data class Error(
        override val id: String,
        override val turnId: String? = null,
        val message: String,
        val code: String? = null,
        val retryable: Boolean = true,
    ) : CodexTimelineItem

    /** Fallback for newer item variants. The raw type remains available for future mapping. */
    data class Unknown(
        override val id: String,
        override val turnId: String? = null,
        val rawType: String,
        val label: String? = null,
    ) : CodexTimelineItem
}

data class CodexTurn(
    val id: String,
    val status: CodexTurnStatus = CodexTurnStatus.UNKNOWN,
    val items: List<CodexTimelineItem> = emptyList(),
    val errorMessage: String? = null,
)

data class CodexTaskDetail(
    val summary: CodexTaskSummary,
    val turns: List<CodexTurn> = emptyList(),
    val pendingActions: List<CodexPendingAction> = emptyList(),
)

internal fun calculateUsageRemainingPercent(
    individualRemainingPercent: Int?,
    usedPercents: List<Int>,
): Int? {
    val candidates = buildList {
        individualRemainingPercent?.let(::add)
        usedPercents.forEach { usedPercent -> add(100 - usedPercent) }
    }
    return candidates.minOrNull()?.coerceIn(0, 100)
}

/** JSON-RPC ids may be either numbers or strings. */
sealed interface CodexRpcId {
    data class NumberValue(val value: Long) : CodexRpcId
    data class StringValue(val value: String) : CodexRpcId
}

data class CodexServerRequestHandle(
    /** A request id is valid only on the connection generation that delivered it. */
    val connectionGeneration: Long,
    val requestId: CodexRpcId,
)

enum class CodexPendingActionKind {
    COMMAND_APPROVAL,
    FILE_CHANGE_APPROVAL,
    PERMISSION_APPROVAL,
    USER_INPUT,
    MCP_ELICITATION,
    UNKNOWN,
}

enum class CodexApprovalDecision(val wireValue: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

data class CodexInputOption(
    val label: String,
    val description: String? = null,
)

data class CodexInputQuestion(
    val id: String,
    val header: String? = null,
    val prompt: String,
    val options: List<CodexInputOption> = emptyList(),
    val allowsFreeform: Boolean = false,
)

/**
 * A user-actionable server request. The handle is intentionally connection-scoped and must not be
 * persisted: app-server does not document replaying request ids after reconnect.
 */
data class CodexPendingAction(
    val handle: CodexServerRequestHandle,
    val kind: CodexPendingActionKind,
    val method: String,
    val threadId: String,
    val turnId: String? = null,
    val itemId: String? = null,
    val title: String,
    val reason: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val filePaths: List<String> = emptyList(),
    /** Raw wire values are retained because newer servers may introduce additional decisions. */
    val availableDecisions: Set<String> = emptySet(),
    val questions: List<CodexInputQuestion> = emptyList(),
    val autoResolutionMs: Long? = null,
)
