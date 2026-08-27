package com.codex.wear.data.codex

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.codex.wear.data.AgentSettings
import com.codex.wear.data.DEFAULT_WATCH_INSTRUCTIONS
import com.codex.wear.data.SettingsRepository
import com.codex.wear.data.TaskDeliveryRegistry
import com.codex.wear.tile.SidekickTileService
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class CodexTaskRepositoryState(
    val tasks: List<CodexTaskSummary> = emptyList(),
    val details: Map<String, CodexTaskDetail> = emptyMap(),
    val connectionState: CodexConnectionState = CodexConnectionState.Disconnected(),
    val isRefreshing: Boolean = false,
    val isUsingCache: Boolean = true,
    val connectionError: String? = null,
    val isCacheLoaded: Boolean = false,
    val usageRemainingPercent: Int? = null,
)

/**
 * The single owner of Codex task state. App-server thread ids are the only task ids exposed by this
 * repository; the watch cache is only a fast summary snapshot and is never treated as authority.
 */
class CodexTaskRepository private constructor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsRepository = SettingsRepository(appContext)
    private val snapshotStore = TaskSnapshotStore(appContext)
    private val refreshMutex = Mutex()
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val persistRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pendingRequests = ConcurrentHashMap<CodexServerRequestHandle, CodexRpcServerRequest>()
    private val locallyStartedTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val noProjectTaskIds = ConcurrentHashMap.newKeySet<String>()

    private var client: CodexRpcClient? = null
    private var clientCollectors: Job? = null
    @Volatile private var latestSettings: AgentSettings = AgentSettings()

    private val _state = MutableStateFlow(CodexTaskRepositoryState())
    val state: StateFlow<CodexTaskRepositoryState> = _state.asStateFlow()

    init {
        scope.launch {
            val cached = runCatching { snapshotStore.load() }.getOrDefault(TaskCacheSnapshot())
            _state.value =
                _state.value.copy(
                    tasks = cached.tasks,
                    isUsingCache = true,
                    isCacheLoaded = true,
                    usageRemainingPercent = cached.usageRemainingPercent,
                )
            settingsRepository.settingsFlow
                .map { settings ->
                    latestSettings = settings
                    ConnectionConfig(settings.baseUrl.trim(), settings.authToken.trim())
                }
                .distinctUntilChanged()
                .collectLatest(::configureClient)
        }
        scope.launch { refreshRequests.collectLatest { refreshNow() } }
        scope.launch {
            persistRequests.collectLatest {
                delay(CACHE_WRITE_COALESCE_MS)
                val cutoff = System.currentTimeMillis() / 1_000L - CACHE_LOOKBACK_SECONDS
                snapshotStore.replaceTasks(_state.value.tasks.filter { it.activityAtEpochSeconds >= cutoff })
            }
        }
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    suspend fun refreshNow() {
        val rpc = client ?: return
        refreshMutex.withLock {
            _state.value = _state.value.copy(isRefreshing = true, connectionError = null)
            runCatching { listRecentTasks(rpc) }
                .onSuccess { serverTasks ->
                    val previous = _state.value.tasks.associateBy(CodexTaskSummary::id)
                    val cached = snapshotStore.load()
                    val reconciled =
                        serverTasks.map { task ->
                            reconcileSummary(task, previous[task.id], cached.seenTaskIds)
                        }
                    _state.value =
                        _state.value.copy(
                            tasks = reconciled.sortedByDescending(CodexTaskSummary::activityAtEpochSeconds),
                            isRefreshing = false,
                            isUsingCache = false,
                            connectionError = null,
                        )
                    requestPersist()
                    runCatching { readUsageRemainingPercent(rpc) }
                        .onSuccess(::updateUsageRemainingPercent)
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            Log.w(TAG, "Usage-limit refresh failed", error)
                        }
                    reconciled.filter { it.state == CodexTaskState.WORKING || it.state == CodexTaskState.NEEDS_ATTENTION }
                        .forEach { task ->
                            runCatching {
                                rpc.requestObject("thread/resume", JSONObject().put("threadId", task.id))
                            }.onFailure { if (it is CancellationException) throw it }
                        }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Task reconciliation failed", error)
                    _state.value =
                        _state.value.copy(
                            isRefreshing = false,
                            connectionError = error.userMessage("Could not connect to Codex"),
                        )
                }
        }
    }

    suspend fun loadTask(taskId: String): CodexTaskDetail {
        val rpc = requireClient()
        val result =
            rpc.requestObject(
                "thread/read",
                JSONObject().put("threadId", taskId).put("includeTurns", true),
            )
        val thread = result.optJSONObject("thread") ?: result
        val previousSummary = _state.value.tasks.firstOrNull { it.id == taskId }
        val detail = parseTaskDetail(thread, previousSummary, pendingActions(taskId))
        updateTask(detail.summary)
        _state.value = _state.value.copy(details = _state.value.details + (taskId to detail))
        markSeen(taskId)
        return detail
    }

    fun loadTaskAsync(taskId: String) {
        scope.launch {
            runCatching { loadTask(taskId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.value = _state.value.copy(connectionError = error.userMessage("Could not load task"))
                }
        }
    }

    suspend fun startTask(input: String, project: CodexProject? = null): CodexTaskSummary {
        val text = input.trim()
        require(text.isNotEmpty()) { "Task input is empty" }
        val rpc = requireClient()
        val settings = latestSettings
        val threadParams =
            JSONObject()
                .put("approvalPolicy", "on-request")
                .put("sandbox", "workspace-write")
                .put("developerInstructions", settings.instructions.ifBlank { DEFAULT_WATCH_INSTRUCTIONS })
                .apply {
                    project?.cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
                    settings.model.takeIf(String::isNotBlank)?.let { put("model", it) }
                }
        val threadResult = rpc.requestObject("thread/start", threadParams)
        val thread = threadResult.optJSONObject("thread") ?: threadResult
        val taskId = thread.optString("id")
        check(taskId.isNotBlank()) { "Codex returned no task id" }
        locallyStartedTaskIds += taskId
        if (project == null) noProjectTaskIds += taskId

        val now = System.currentTimeMillis() / 1_000L
        val initial =
            CodexTaskSummary(
                id = taskId,
                title = text.replace(Regex("\\s+"), " ").take(TITLE_PREVIEW_CHARS),
                preview = text,
                state = CodexTaskState.WORKING,
                threadStatus = CodexThreadStatus(CodexThreadStatusType.ACTIVE),
                project = project,
                cwd = project?.cwd,
                createdAtEpochSeconds = now,
                updatedAtEpochSeconds = now,
                recencyAtEpochSeconds = now,
                isUnread = false,
            )
        updateTask(initial)

        val turnResult =
            try {
                rpc.requestObject("turn/start", buildTurnParams(taskId, text, settings.model))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                updateTask(
                    initial.copy(
                        state = CodexTaskState.ERROR,
                        threadStatus = CodexThreadStatus(CodexThreadStatusType.SYSTEM_ERROR),
                        errorMessage = error.userMessage("Could not start task"),
                    ),
                )
                throw error
            }
        val turn = turnResult.optJSONObject("turn") ?: turnResult
        val turnId = turn.optString("id").takeIf(String::isNotBlank)
        val started = initial.copy(latestTurnId = turnId)
        updateTask(started)
        return started
    }

    suspend fun followUp(taskId: String, input: String): CodexTaskSummary {
        val text = input.trim()
        require(text.isNotEmpty()) { "Task input is empty" }
        val rpc = requireClient()
        rpc.requestObject("thread/resume", JSONObject().put("threadId", taskId))
        locallyStartedTaskIds += taskId
        val result =
            try {
                rpc.requestObject("turn/start", buildTurnParams(taskId, text, latestSettings.model))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                updateExistingTask(taskId) {
                    it.copy(
                        state = CodexTaskState.ERROR,
                        threadStatus = CodexThreadStatus(CodexThreadStatusType.SYSTEM_ERROR),
                        errorMessage = error.userMessage("Could not continue task"),
                    )
                }
                throw error
            }
        val turn = result.optJSONObject("turn") ?: result
        val current = _state.value.tasks.firstOrNull { it.id == taskId }
            ?: CodexTaskSummary(id = taskId)
        val updated =
            current.copy(
                state = CodexTaskState.WORKING,
                threadStatus = CodexThreadStatus(CodexThreadStatusType.ACTIVE),
                latestTurnId = turn.optString("id").takeIf(String::isNotBlank),
                updatedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                isUnread = false,
            )
        updateTask(updated)
        return updated
    }

    suspend fun interrupt(taskId: String) {
        val task = _state.value.tasks.firstOrNull { it.id == taskId }
            ?: error("Task is not loaded")
        val turnId = task.latestTurnId ?: error("Task has no active turn")
        requireClient().requestObject(
            "turn/interrupt",
            JSONObject().put("threadId", taskId).put("turnId", turnId),
        )
        updateTask(
            task.copy(
                state = CodexTaskState.IDLE,
                threadStatus = CodexThreadStatus.Idle,
                isUnread = false,
            ),
        )
    }

    suspend fun decide(action: CodexPendingAction, decision: CodexApprovalDecision) {
        val rpc = requireClient()
        val serverRequest = pendingRequests[action.handle]
            ?: throw CodexConnectionException("This approval is no longer available")
        if (action.availableDecisions.isNotEmpty() && decision.wireValue !in action.availableDecisions) {
            throw IllegalArgumentException("${decision.wireValue} is not available for this approval")
        }
        val params = serverRequest.objectParams ?: JSONObject()
        val result =
            when (action.kind) {
                CodexPendingActionKind.COMMAND_APPROVAL,
                CodexPendingActionKind.FILE_CHANGE_APPROVAL,
                -> JSONObject().put("decision", decision.wireValue)
                CodexPendingActionKind.PERMISSION_APPROVAL ->
                    JSONObject()
                        .put(
                            "permissions",
                            if (decision == CodexApprovalDecision.ACCEPT || decision == CodexApprovalDecision.ACCEPT_FOR_SESSION) {
                                params.optJSONObject("permissions") ?: JSONObject()
                            } else {
                                JSONObject()
                            },
                        )
                        .put(
                            "scope",
                            if (decision == CodexApprovalDecision.ACCEPT_FOR_SESSION) "session" else "turn",
                        )
                CodexPendingActionKind.USER_INPUT -> buildUserInputResponse(action, decision)
                CodexPendingActionKind.MCP_ELICITATION ->
                    if (decision == CodexApprovalDecision.ACCEPT || decision == CodexApprovalDecision.ACCEPT_FOR_SESSION) {
                        throw IllegalArgumentException("This request needs structured input before it can be accepted")
                    } else {
                        JSONObject()
                            .put("action", decision.wireValue)
                            .put("content", JSONObject.NULL)
                    }
                CodexPendingActionKind.UNKNOWN ->
                    JSONObject().put("decision", decision.wireValue)
            }
        rpc.respond(action.handle, result)
        pendingRequests.remove(action.handle)
        removePendingAction(action)
    }

    suspend fun markSeen(taskId: String) {
        snapshotStore.markSeen(taskId)
        _state.value =
            _state.value.copy(
                tasks = _state.value.tasks.map { if (it.id == taskId) it.copy(isUnread = false) else it },
            )
        requestPersist()
    }

    suspend fun markCompletionShown(taskId: String) {
        TaskDeliveryRegistry.markDelivered(taskId)
        snapshotStore.markCompletionNotified(taskId)
    }

    fun recentProjects(limit: Int = 5): List<CodexProject> =
        _state.value.tasks
            .sortedByDescending(CodexTaskSummary::activityAtEpochSeconds)
            .mapNotNull(CodexTaskSummary::project)
            .distinctBy(CodexProject::id)
            .take(limit)

    override fun close() {
        clientCollectors?.cancel()
        client?.close()
        scope.cancel()
    }

    private suspend fun configureClient(config: ConnectionConfig) {
        clientCollectors?.cancel()
        client?.close()
        client = null
        pendingRequests.clear()
        if (config.serverUrl.isBlank()) {
            _state.value = _state.value.copy(connectionError = "Set the Codex server URL in Settings")
            return
        }
        val rpc =
            runCatching { CodexRpcClient(config.serverUrl, config.authToken, scope) }
                .getOrElse { error ->
                    _state.value = _state.value.copy(connectionError = error.userMessage("Invalid Codex server URL"))
                    return
                }
        client = rpc
        clientCollectors =
            scope.launch {
                launch {
                    rpc.connectionState.collectLatest { connection ->
                        _state.value = _state.value.copy(connectionState = connection)
                        if (connection is CodexConnectionState.Connected) {
                            refreshRequests.tryEmit(Unit)
                        } else {
                            expirePendingActions()
                        }
                    }
                }
                launch { rpc.notifications.collect(::handleNotification) }
                launch { rpc.serverRequests.collect(::handleServerRequest) }
            }
        rpc.connect()
    }

    private suspend fun listRecentTasks(rpc: CodexRpcClient): List<CodexTaskSummary> {
        val cutoff = System.currentTimeMillis() / 1_000L - CACHE_LOOKBACK_SECONDS
        val tasks = mutableListOf<CodexTaskSummary>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val params =
                JSONObject()
                    .put("limit", LIST_PAGE_SIZE)
                    .put("sortKey", "recency_at")
                    .put("sourceKinds", JSONArray(listOf("cli", "vscode", "appServer")))
                    .apply { cursor?.let { put("cursor", it) } }
            val result = rpc.requestObject("thread/list", params)
            val rows = result.optJSONArray("data") ?: result.optJSONArray("threads") ?: JSONArray()
            val page = rows.objects().mapNotNull { parseTaskSummary(it, CodexTaskState.COMPLETE) }
            tasks += page.filter { it.activityAtEpochSeconds >= cutoff }
            val next = result.optNullableString("nextCursor")
            val reachedCutoff = page.isNotEmpty() && page.minOf(CodexTaskSummary::activityAtEpochSeconds) < cutoff
            if (next == null || reachedCutoff) return tasks.distinctBy(CodexTaskSummary::id)
            cursor = next
        }
        return tasks.distinctBy(CodexTaskSummary::id)
    }

    private suspend fun readUsageRemainingPercent(rpc: CodexRpcClient): Int? =
        parseUsageRemainingPercent(
            rpc.requestObject("account/rateLimits/read", params = null),
        )

    private fun reconcileSummary(
        server: CodexTaskSummary,
        previous: CodexTaskSummary?,
        seenTaskIds: Set<String>,
    ): CodexTaskSummary {
        val preserveNoProject =
            server.id in noProjectTaskIds || previous?.let { it.project == null && it.cwd == null } == true
        val transitionedToComplete =
            (server.state == CodexTaskState.IDLE || server.state == CodexTaskState.COMPLETE) &&
                (previous?.state == CodexTaskState.WORKING || previous?.state == CodexTaskState.NEEDS_ATTENTION)
        val nextState =
            when {
                transitionedToComplete -> CodexTaskState.COMPLETE
                (server.state == CodexTaskState.IDLE || server.state == CodexTaskState.COMPLETE) &&
                    previous?.state == CodexTaskState.COMPLETE -> CodexTaskState.COMPLETE
                else -> server.state
            }
        val unread =
            when {
                transitionedToComplete -> true
                server.id in seenTaskIds -> false
                nextState == CodexTaskState.COMPLETE -> previous?.isUnread == true
                else -> false
            }
        return server.copy(
            state = nextState,
            project = if (preserveNoProject) null else server.project,
            isUnread = unread,
        )
    }

    private fun handleNotification(notification: CodexRpcNotification) {
        val params = notification.objectParams ?: return
        when (notification.method) {
            "thread/started" -> {
                val thread = params.optJSONObject("thread") ?: return
                parseTaskSummary(thread)?.let(::updateTask)
            }
            "thread/status/changed" -> {
                val taskId = params.threadId() ?: return
                val status = parseThreadStatus(params.opt("status"))
                updateExistingTask(taskId) { task ->
                    task.copy(
                        threadStatus = status,
                        state = status.toTaskState(task),
                        isUnread = if (status.type == CodexThreadStatusType.ACTIVE) false else task.isUnread,
                    )
                }
            }
            "thread/name/updated" -> {
                val taskId = params.threadId() ?: return
                val title =
                    params.optString("threadName").ifBlank { params.optString("name") }
                        .takeIf(String::isNotBlank)
                updateExistingTask(taskId) { it.copy(title = title) }
            }
            "turn/started" -> handleTurnStarted(params)
            "turn/completed" -> handleTurnCompleted(params)
            "item/started", "item/completed" -> handleStructuredItem(params)
            "item/agentMessage/delta" -> handleAgentDelta(params)
            "serverRequest/resolved" -> handleServerRequestResolved(params)
            "account/rateLimits/updated" -> updateUsageRemainingPercent(
                parseUsageRemainingPercent(params),
            )
            "thread/archived", "thread/unarchived", "thread/closed", "thread/project/updated" ->
                refreshRequests.tryEmit(Unit)
            "error" -> handleErrorNotification(params)
        }
    }

    private fun handleServerRequest(request: CodexRpcServerRequest) {
        val parsedAction = parsePendingAction(request)
        val action =
            parsedAction?.let { candidate ->
                if (candidate.kind != CodexPendingActionKind.FILE_CHANGE_APPROVAL || candidate.filePaths.isNotEmpty()) {
                    candidate
                } else {
                    candidate.copy(filePaths = filePathsForItem(candidate.threadId, candidate.itemId))
                }
            }
        if (action == null) {
            scope.launch {
                runCatching { client?.respondError(request.handle, -32_601, "Unsupported watch request") }
            }
            return
        }
        pendingRequests[action.handle] = request
        val existing = _state.value.details[action.threadId]
        if (existing != null) {
            _state.value =
                _state.value.copy(
                    details = _state.value.details +
                        (action.threadId to existing.copy(pendingActions = existing.pendingActions + action)),
                )
        }
        updateExistingTask(action.threadId) {
            val waitingFlag =
                if (action.kind == CodexPendingActionKind.USER_INPUT) {
                    CodexThreadStatus.WAITING_ON_USER_INPUT
                } else {
                    CodexThreadStatus.WAITING_ON_APPROVAL
                }
            it.copy(
                state = CodexTaskState.NEEDS_ATTENTION,
                threadStatus =
                    CodexThreadStatus(
                        type = CodexThreadStatusType.ACTIVE,
                        activeFlags = setOf(waitingFlag),
                    ),
            )
        }
    }

    private fun handleServerRequestResolved(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val requestId = params.opt("requestId").toCodexRpcIdOrNull() ?: return
        val entry = pendingRequests.entries.firstOrNull { (handle, request) ->
            handle.requestId == requestId && request.objectParams?.threadId() == taskId
        } ?: return
        if (pendingRequests.remove(entry.key, entry.value)) {
            parsePendingAction(entry.value)?.let(::removePendingAction)
        }
    }

    private fun handleTurnStarted(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val turn = params.optJSONObject("turn")
        val turnId = turn?.optString("id")?.takeIf(String::isNotBlank) ?: params.optString("turnId").takeIf(String::isNotBlank)
        updateExistingTask(taskId) {
            it.copy(
                state = CodexTaskState.WORKING,
                threadStatus = CodexThreadStatus(CodexThreadStatusType.ACTIVE),
                latestTurnId = turnId,
                isUnread = false,
            )
        }
    }

    private fun handleTurnCompleted(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val turn = params.optJSONObject("turn") ?: JSONObject()
        val status = parseTurnStatus(turn.optString("status"))
        val error = turn.optJSONObject("error")?.optString("message").takeIf { !it.isNullOrBlank() }
        val state =
            when (status) {
                CodexTurnStatus.FAILED -> CodexTaskState.ERROR
                CodexTurnStatus.COMPLETED -> CodexTaskState.COMPLETE
                CodexTurnStatus.INTERRUPTED, CodexTurnStatus.UNKNOWN -> CodexTaskState.IDLE
                CodexTurnStatus.IN_PROGRESS -> CodexTaskState.WORKING
            }
        updateExistingTask(taskId) {
            it.copy(
                state = state,
                threadStatus =
                    when (state) {
                        CodexTaskState.ERROR -> CodexThreadStatus(CodexThreadStatusType.SYSTEM_ERROR)
                        CodexTaskState.WORKING -> CodexThreadStatus(CodexThreadStatusType.ACTIVE)
                        else -> CodexThreadStatus.Idle
                    },
                updatedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                latestTurnId = turn.optString("id").takeIf(String::isNotBlank) ?: it.latestTurnId,
                isUnread = state == CodexTaskState.COMPLETE || state == CodexTaskState.ERROR,
                errorMessage = error,
            )
        }
        locallyStartedTaskIds.remove(taskId)
        removePendingActionsForTask(taskId)
        val detail = _state.value.details[taskId]
        if (detail != null) {
            val completedTurn = parseTurn(turn)
            val turns =
                if (completedTurn == null) detail.turns
                else {
                    val existingTurn = detail.turns.firstOrNull { it.id == completedTurn.id }
                    val mergedTurn =
                        if (completedTurn.items.isEmpty() && existingTurn != null) {
                            completedTurn.copy(items = existingTurn.items)
                        } else {
                            completedTurn
                        }
                    detail.turns.filterNot { it.id == mergedTurn.id } + mergedTurn
                }
            _state.value = _state.value.copy(details = _state.value.details + (taskId to detail.copy(turns = turns)))
        }
    }

    private fun handleStructuredItem(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val itemJson = params.optJSONObject("item") ?: return
        val item = parseTimelineItem(itemJson, params.optString("turnId")) ?: return
        val detail = _state.value.details[taskId] ?: return
        val turnId = item.turnId ?: detail.turns.lastOrNull()?.id ?: return
        val turns = detail.turns.upsertItem(turnId, item)
        _state.value = _state.value.copy(details = _state.value.details + (taskId to detail.copy(turns = turns)))
    }

    private fun handleAgentDelta(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val delta = params.optString("delta")
        if (delta.isEmpty()) return
        val itemId = params.optString("itemId").ifBlank { "streaming-agent" }
        val turnId = params.optString("turnId").takeIf(String::isNotBlank)
        val detail = _state.value.details[taskId]
        if (detail != null) {
            val existing = detail.turns.flatMap { it.items }.filterIsInstance<CodexTimelineItem.AgentMessage>()
                .firstOrNull { it.id == itemId }
            val message = CodexTimelineItem.AgentMessage(itemId, turnId, existing?.text.orEmpty() + delta)
            val targetTurnId = turnId ?: detail.turns.lastOrNull()?.id
            if (targetTurnId != null) {
                _state.value =
                    _state.value.copy(
                        details = _state.value.details +
                            (taskId to detail.copy(turns = detail.turns.upsertItem(targetTurnId, message))),
                    )
            }
        }
        updateExistingTask(taskId) { it.copy(preview = (it.preview + delta).takeLast(TASK_PREVIEW_CHARS)) }
    }

    private fun handleErrorNotification(params: JSONObject) {
        val taskId = params.threadId() ?: return
        val message = params.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { params.optString("message", "Codex task failed") }
        updateExistingTask(taskId) {
            it.copy(state = CodexTaskState.ERROR, errorMessage = message, isUnread = true)
        }
    }

    private fun updateTask(task: CodexTaskSummary) {
        val tasks = (_state.value.tasks.filterNot { it.id == task.id } + task)
            .sortedByDescending(CodexTaskSummary::activityAtEpochSeconds)
        val detail = _state.value.details[task.id]
        val details =
            if (detail == null) _state.value.details
            else _state.value.details + (task.id to detail.copy(summary = task))
        _state.value = _state.value.copy(tasks = tasks, details = details)
        requestPersist()
    }

    private fun updateExistingTask(taskId: String, transform: (CodexTaskSummary) -> CodexTaskSummary) {
        val existing = _state.value.tasks.firstOrNull { it.id == taskId }
            ?: CodexTaskSummary(id = taskId, updatedAtEpochSeconds = System.currentTimeMillis() / 1_000L)
        updateTask(transform(existing))
    }

    private fun removePendingAction(action: CodexPendingAction) {
        val detail = _state.value.details[action.threadId]
        if (detail != null) {
            _state.value =
                _state.value.copy(
                    details = _state.value.details +
                        (action.threadId to detail.copy(pendingActions = detail.pendingActions - action)),
                )
        }
        val stillPending = pendingActions(action.threadId).isNotEmpty()
        if (!stillPending) {
            updateExistingTask(action.threadId) {
                if (it.state == CodexTaskState.NEEDS_ATTENTION &&
                    it.threadStatus.type == CodexThreadStatusType.ACTIVE
                ) {
                    it.copy(
                        state = CodexTaskState.WORKING,
                        threadStatus = CodexThreadStatus(CodexThreadStatusType.ACTIVE),
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun removePendingActionsForTask(taskId: String) {
        pendingRequests.entries.removeIf { (_, request) -> request.objectParams?.threadId() == taskId }
        val detail = _state.value.details[taskId] ?: return
        if (detail.pendingActions.isNotEmpty()) {
            _state.value =
                _state.value.copy(
                    details = _state.value.details + (taskId to detail.copy(pendingActions = emptyList())),
                )
        }
    }

    private fun expirePendingActions() {
        if (pendingRequests.isEmpty()) return
        pendingRequests.clear()
        _state.value =
            _state.value.copy(
                details = _state.value.details.mapValues { (_, detail) ->
                    if (detail.pendingActions.isEmpty()) detail else detail.copy(pendingActions = emptyList())
                },
            )
    }

    private fun filePathsForItem(taskId: String, itemId: String?): List<String> {
        if (itemId == null) return emptyList()
        return _state.value.details[taskId]
            ?.turns
            ?.asSequence()
            ?.flatMap { it.items.asSequence() }
            ?.filterIsInstance<CodexTimelineItem.FileChanges>()
            ?.firstOrNull { it.id == itemId }
            ?.changes
            ?.map(CodexFileChange::path)
            .orEmpty()
    }

    private fun pendingActions(taskId: String): List<CodexPendingAction> =
        pendingRequests.values.mapNotNull(::parsePendingAction).filter { it.threadId == taskId }

    private fun requestPersist() {
        persistRequests.tryEmit(Unit)
    }

    private fun updateUsageRemainingPercent(remainingPercent: Int?) {
        _state.value = _state.value.copy(usageRemainingPercent = remainingPercent)
        scope.launch { snapshotStore.replaceUsageRemainingPercent(remainingPercent) }
        TileService.getUpdater(appContext).requestUpdate(SidekickTileService::class.java)
    }

    private fun requireClient(): CodexRpcClient =
        client ?: throw CodexConnectionException("Codex is not connected")

    companion object {
        @Volatile private var instance: CodexTaskRepository? = null

        fun get(context: Context): CodexTaskRepository =
            instance ?: synchronized(this) {
                instance ?: CodexTaskRepository(context).also { instance = it }
            }

        private const val TAG = "CodexTaskRepository"
        private const val LIST_PAGE_SIZE = 50
        private const val MAX_LIST_PAGES = 8
        private const val CACHE_LOOKBACK_SECONDS = 7L * 24L * 60L * 60L
        private const val CACHE_WRITE_COALESCE_MS = 180L
        private const val TITLE_PREVIEW_CHARS = 64
        private const val TASK_PREVIEW_CHARS = 320
    }
}

private data class ConnectionConfig(val serverUrl: String, val authToken: String)

private fun parseUsageRemainingPercent(root: JSONObject): Int? {
    val limits = root.optJSONObject("rateLimits") ?: root
    val individual = limits.optJSONObject("individualLimit")
    val individualRemaining =
        when {
            limits.optBoolean("spendControlReached", false) -> 0
            individual?.has("remainingPercent") == true -> individual.optInt("remainingPercent")
            else -> null
        }
    val usedPercents = listOfNotNull(
        limits.optJSONObject("primary")?.usedPercentOrNull(),
        limits.optJSONObject("secondary")?.usedPercentOrNull(),
    )
    val calculated = calculateUsageRemainingPercent(individualRemaining, usedPercents)
    return calculated ?: if (!limits.isNull("rateLimitReachedType")) 0 else null
}

private fun JSONObject.usedPercentOrNull(): Int? =
    if (has("usedPercent") && !isNull("usedPercent")) optInt("usedPercent") else null

private fun buildTurnParams(taskId: String, input: String, model: String): JSONObject =
    JSONObject()
        .put("threadId", taskId)
        .put(
            "input",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put("text", input)
                    .put("text_elements", JSONArray()),
            ),
        )
        .apply { model.takeIf(String::isNotBlank)?.let { put("model", it) } }

private fun buildUserInputResponse(
    action: CodexPendingAction,
    decision: CodexApprovalDecision,
): JSONObject {
    val answers = JSONObject()
    action.questions.forEach { question ->
        val selected = question.options.firstOrNull { it.label.matchesDecision(decision) }
        if (selected == null &&
            (decision == CodexApprovalDecision.ACCEPT || decision == CodexApprovalDecision.ACCEPT_FOR_SESSION)
        ) {
            throw IllegalArgumentException("This request needs an explicit answer")
        }
        answers.put(
            question.id,
            JSONObject().put(
                "answers",
                JSONArray().apply { selected?.label?.let(::put) },
            ),
        )
    }
    return JSONObject().put("answers", answers)
}

private fun String.matchesDecision(decision: CodexApprovalDecision): Boolean {
    val normalized = trim().lowercase()
    return when (decision) {
        CodexApprovalDecision.ACCEPT,
        CodexApprovalDecision.ACCEPT_FOR_SESSION,
        -> normalized == "accept" || normalized == "approve" || normalized == "allow" ||
            normalized == "yes" || normalized.startsWith("allow ")
        CodexApprovalDecision.DECLINE ->
            normalized == "decline" || normalized == "deny" || normalized == "reject" || normalized == "no"
        CodexApprovalDecision.CANCEL -> normalized == "cancel" || normalized == "stop"
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback

private fun parseTaskSummary(
    json: JSONObject,
    inactiveState: CodexTaskState = CodexTaskState.IDLE,
): CodexTaskSummary? {
    val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
    val cwd = json.optNullableString("cwd")
    val status = parseThreadStatus(json.opt("status"))
    val turns = json.optJSONArray("turns")
    val latestTurn = turns?.optJSONObject((turns.length() - 1).coerceAtLeast(0))
    val latestTurnStatus = parseTurnStatus(latestTurn?.optString("status").orEmpty())
    val state =
        when {
            status.type == CodexThreadStatusType.SYSTEM_ERROR || latestTurnStatus == CodexTurnStatus.FAILED -> CodexTaskState.ERROR
            status.needsAttention -> CodexTaskState.NEEDS_ATTENTION
            status.type == CodexThreadStatusType.ACTIVE -> CodexTaskState.WORKING
            latestTurnStatus == CodexTurnStatus.COMPLETED -> CodexTaskState.COMPLETE
            else -> inactiveState
        }
    val source = json.opt("source")
    val sourceKind =
        when (source) {
            is JSONObject ->
                source.optString("kind").ifBlank {
                    source.optString("type").ifBlank {
                        when {
                            source.has("custom") -> "custom"
                            source.has("subAgent") -> "subAgent"
                            else -> ""
                        }
                    }
                }
            is String -> source
            else -> json.optString("sourceKind")
        }.takeIf(String::isNotBlank)
    val projectId = json.optNullableString("projectId")
    val project =
        (projectId ?: cwd)?.let { id ->
            val path = cwd
            CodexProject(
                id = id,
                name = path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { id } ?: id,
                cwd = path,
                lastUsedAtEpochSeconds = json.epochSeconds("recencyAt", "recency_at", "updatedAt", "updated_at"),
            )
        }
    return CodexTaskSummary(
        id = id,
        title = json.optNullableString("name") ?: json.optNullableString("title"),
        preview =
            json.optString("preview").ifBlank {
                json.optString("firstUserMessage").ifBlank { json.optString("first_user_message") }
            },
        state = state,
        threadStatus = status,
        project = project,
        cwd = cwd,
        sourceKind = sourceKind,
        createdAtEpochSeconds = json.epochSeconds("createdAt", "created_at"),
        updatedAtEpochSeconds = json.epochSeconds("updatedAt", "updated_at"),
        recencyAtEpochSeconds =
            json.epochSeconds("recencyAt", "recency_at").takeIf { it > 0L },
        latestTurnId = latestTurn?.optString("id")?.takeIf(String::isNotBlank),
        errorMessage = latestTurn?.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank),
    )
}

private fun parseTaskDetail(
    thread: JSONObject,
    previousSummary: CodexTaskSummary?,
    pendingActions: List<CodexPendingAction>,
): CodexTaskDetail {
    val parsed = parseTaskSummary(thread) ?: previousSummary ?: error("Codex returned an invalid task")
    val turns = thread.optJSONArray("turns").objects().mapNotNull(::parseTurn)
    val latest = turns.lastOrNull()
    val derivedState =
        when {
            pendingActions.isNotEmpty() -> CodexTaskState.NEEDS_ATTENTION
            latest?.status == CodexTurnStatus.IN_PROGRESS -> CodexTaskState.WORKING
            latest?.status == CodexTurnStatus.FAILED -> CodexTaskState.ERROR
            latest?.status == CodexTurnStatus.COMPLETED -> CodexTaskState.COMPLETE
            else -> parsed.state
        }
    val summary =
        parsed.copy(
            state = derivedState,
            project = previousSummary?.project ?: parsed.project,
            isUnread = previousSummary?.isUnread == true,
            latestTurnId = latest?.id ?: parsed.latestTurnId,
            errorMessage = latest?.errorMessage ?: parsed.errorMessage,
        )
    return CodexTaskDetail(summary = summary, turns = turns, pendingActions = pendingActions)
}

private fun parseTurn(json: JSONObject): CodexTurn? {
    val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
    val items = json.optJSONArray("items").objects().mapNotNull { parseTimelineItem(it, id) }
    return CodexTurn(
        id = id,
        status = parseTurnStatus(json.optString("status")),
        items = items,
        errorMessage = json.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank),
    )
}

private fun parseTurnStatus(raw: String): CodexTurnStatus =
    when (raw.lowercase()) {
        "inprogress", "in_progress", "running", "active" -> CodexTurnStatus.IN_PROGRESS
        "completed", "complete", "succeeded", "success" -> CodexTurnStatus.COMPLETED
        "interrupted", "cancelled", "canceled" -> CodexTurnStatus.INTERRUPTED
        "failed", "error" -> CodexTurnStatus.FAILED
        else -> CodexTurnStatus.UNKNOWN
    }

private fun parseTimelineItem(json: JSONObject, fallbackTurnId: String? = null): CodexTimelineItem? {
    val type = json.optString("type").ifBlank { "unknown" }
    val id = json.optString("id").takeIf(String::isNotBlank) ?: "$type-${json.hashCode()}"
    val turnId = json.optString("turnId").takeIf(String::isNotBlank) ?: fallbackTurnId
    return when (type) {
        "userMessage" -> parseUserMessage(json, id, turnId)
        "agentMessage" ->
            CodexTimelineItem.AgentMessage(
                id = id,
                turnId = turnId,
                text = json.optString("text").ifBlank { json.optString("content") },
                phase =
                    when (json.optString("phase")) {
                        "commentary" -> CodexAgentMessagePhase.COMMENTARY
                        "final_answer", "finalAnswer" -> CodexAgentMessagePhase.FINAL_ANSWER
                        else -> CodexAgentMessagePhase.UNKNOWN
                    },
                rawPhase = json.optNullableString("phase"),
            )
        "commandExecution" ->
            CodexTimelineItem.CommandExecution(
                id = id,
                turnId = turnId,
                command = json.opt("command").wireText().ifBlank { "Command" },
                cwd = json.optNullableString("cwd"),
                status = parseItemStatus(json.optString("status")),
                outputPreview =
                    json.optNullableString("aggregatedOutput")
                        ?: json.optNullableString("output")
                        ?: json.optNullableString("outputPreview"),
                exitCode = json.optNullableInt("exitCode"),
                durationMs = json.optNullableLong("durationMs"),
            )
        "fileChange" ->
            CodexTimelineItem.FileChanges(
                id = id,
                turnId = turnId,
                changes = parseFileChanges(json),
                status = parseItemStatus(json.optString("status")),
            )
        "mcpToolCall", "dynamicToolCall", "collabAgentToolCall", "collabToolCall" ->
            CodexTimelineItem.ToolCall(
                id = id,
                turnId = turnId,
                server =
                    json.optNullableString("server")
                        ?: json.optNullableString("namespace"),
                tool = json.optString("tool").ifBlank { json.optString("name", "Tool") },
                status = parseItemStatus(json.optString("status")),
                argumentsSummary =
                    json.opt("arguments").wireText().takeIf(String::isNotBlank)
                        ?: json.optNullableString("prompt"),
                resultSummary =
                    json.opt("result").wireText().takeIf(String::isNotBlank)
                        ?: json.opt("contentItems").wireText().takeIf(String::isNotBlank),
                errorMessage = json.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank),
            )
        "webSearch" ->
            CodexTimelineItem.ToolCall(
                id = id,
                turnId = turnId,
                tool = "Web search",
                status = parseItemStatus(json.optString("status")),
                argumentsSummary = json.optString("query").takeIf(String::isNotBlank),
            )
        "imageView" -> {
            val value = json.optString("path").ifBlank { json.optString("url") }
            CodexTimelineItem.ImageItem(
                id = id,
                turnId = turnId,
                image =
                    CodexImage(
                        value = value,
                        source = if (value.startsWith("http")) CodexImageSource.REMOTE_URL else CodexImageSource.SERVER_LOCAL_PATH,
                    ),
            )
        }
        "error" ->
            CodexTimelineItem.Error(
                id = id,
                turnId = turnId,
                message = json.optString("message", "Codex task failed"),
                code = json.optNullableString("code"),
                retryable = json.optBoolean("retryable", true),
            )
        else ->
            CodexTimelineItem.Unknown(
                id = id,
                turnId = turnId,
                rawType = type,
                label = json.optNullableString("name") ?: json.optNullableString("title"),
            )
    }
}

private fun parseUserMessage(json: JSONObject, id: String, turnId: String?): CodexTimelineItem.UserMessage {
    val textParts = mutableListOf<String>()
    val images = mutableListOf<CodexImage>()
    when (val content = json.opt("content")) {
        is String -> textParts += content
        is JSONArray -> content.objects().forEach { part ->
            when (part.optString("type")) {
                "text", "inputText" -> part.optString("text").takeIf(String::isNotBlank)?.let(textParts::add)
                "image", "inputImage" -> {
                    val value = part.optString("url").ifBlank { part.optString("imageUrl") }
                    if (value.isNotBlank()) images += CodexImage(value, CodexImageSource.REMOTE_URL)
                }
                "localImage" -> {
                    val value = part.optString("path")
                    if (value.isNotBlank()) images += CodexImage(value, CodexImageSource.SERVER_LOCAL_PATH)
                }
            }
        }
    }
    json.optString("text").takeIf(String::isNotBlank)?.let { if (it !in textParts) textParts += it }
    return CodexTimelineItem.UserMessage(id, turnId, textParts.joinToString("\n"), images)
}

private fun parseFileChanges(json: JSONObject): List<CodexFileChange> {
    val rows = json.optJSONArray("changes") ?: json.optJSONArray("files") ?: JSONArray()
    return rows.objects().mapNotNull { change ->
        val path = change.optString("path").takeIf(String::isNotBlank) ?: return@mapNotNull null
        CodexFileChange(
            path = path,
            kind = change.optNullableString("kind") ?: change.optNullableString("type"),
            diff = change.optNullableString("diff"),
        )
    }
}

private fun parseItemStatus(raw: String): CodexTimelineItemStatus =
    when (raw.lowercase()) {
        "inprogress", "in_progress", "running", "active" -> CodexTimelineItemStatus.IN_PROGRESS
        "completed", "complete", "succeeded", "success" -> CodexTimelineItemStatus.COMPLETED
        "failed", "error" -> CodexTimelineItemStatus.FAILED
        "declined", "denied", "cancelled", "canceled" -> CodexTimelineItemStatus.DECLINED
        else -> CodexTimelineItemStatus.UNKNOWN
    }

private fun parseThreadStatus(raw: Any?): CodexThreadStatus {
    val typeValue: String
    val flags: Set<String>
    when (raw) {
        is JSONObject -> {
            typeValue = raw.optString("type")
            flags = (raw.optJSONArray("activeFlags") ?: raw.optJSONArray("flags")).strings().toSet()
        }
        is String -> {
            typeValue = raw
            flags = emptySet()
        }
        else -> {
            typeValue = "notLoaded"
            flags = emptySet()
        }
    }
    val type =
        when (typeValue.lowercase()) {
            "notloaded", "not_loaded" -> CodexThreadStatusType.NOT_LOADED
            "idle" -> CodexThreadStatusType.IDLE
            "active", "running", "inprogress", "in_progress" -> CodexThreadStatusType.ACTIVE
            "systemerror", "system_error", "error", "failed" -> CodexThreadStatusType.SYSTEM_ERROR
            else -> CodexThreadStatusType.UNKNOWN
        }
    return CodexThreadStatus(type = type, activeFlags = flags, rawType = typeValue.takeIf(String::isNotBlank))
}

private fun CodexThreadStatus.toTaskState(previous: CodexTaskSummary): CodexTaskState =
    when {
        type == CodexThreadStatusType.SYSTEM_ERROR -> CodexTaskState.ERROR
        needsAttention -> CodexTaskState.NEEDS_ATTENTION
        type == CodexThreadStatusType.ACTIVE -> CodexTaskState.WORKING
        previous.state == CodexTaskState.WORKING || previous.state == CodexTaskState.NEEDS_ATTENTION -> CodexTaskState.COMPLETE
        previous.state == CodexTaskState.COMPLETE -> CodexTaskState.COMPLETE
        else -> CodexTaskState.IDLE
    }

private fun parsePendingAction(request: CodexRpcServerRequest): CodexPendingAction? {
    val params = request.objectParams ?: return null
    val kind =
        when (request.method) {
            "item/commandExecution/requestApproval" -> CodexPendingActionKind.COMMAND_APPROVAL
            "item/fileChange/requestApproval" -> CodexPendingActionKind.FILE_CHANGE_APPROVAL
            "item/permissions/requestApproval" -> CodexPendingActionKind.PERMISSION_APPROVAL
            "item/tool/requestUserInput" -> CodexPendingActionKind.USER_INPUT
            "mcpServer/elicitation/request" -> CodexPendingActionKind.MCP_ELICITATION
            else -> return null
        }
    val threadId = params.threadId() ?: return null
    val command = params.opt("command").wireText().takeIf(String::isNotBlank)
    val files =
        (params.optJSONArray("changes") ?: params.optJSONArray("files"))
            .objects()
            .mapNotNull { it.optString("path").takeIf(String::isNotBlank) }
    val questions = params.optJSONArray("questions").objects().mapNotNull { question ->
        val id = question.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        CodexInputQuestion(
            id = id,
            header = question.optNullableString("header"),
            prompt = question.optString("question").ifBlank { question.optString("prompt", "Input required") },
            options = question.optJSONArray("options").objects().map { option ->
                CodexInputOption(option.optString("label"), option.optNullableString("description"))
            },
            allowsFreeform =
                question.optBoolean(
                    "allowsFreeform",
                    question.optBoolean("isOther", false),
                ),
        )
    }
    val title =
        when (kind) {
            CodexPendingActionKind.COMMAND_APPROVAL -> "Run this command?"
            CodexPendingActionKind.FILE_CHANGE_APPROVAL -> "Apply these file changes?"
            CodexPendingActionKind.PERMISSION_APPROVAL -> "Grant requested access?"
            CodexPendingActionKind.USER_INPUT -> questions.firstOrNull()?.prompt ?: "Codex needs input"
            CodexPendingActionKind.MCP_ELICITATION -> params.optString("message", "A tool needs approval")
            CodexPendingActionKind.UNKNOWN -> "Codex needs attention"
        }
    return CodexPendingAction(
        handle = request.handle,
        kind = kind,
        method = request.method,
        threadId = threadId,
        turnId = params.optString("turnId").takeIf(String::isNotBlank),
        itemId = params.optString("itemId").takeIf(String::isNotBlank),
        title = title,
        reason = params.optNullableString("reason"),
        command = command,
        cwd = params.optNullableString("cwd"),
        filePaths = files,
        availableDecisions = params.optJSONArray("availableDecisions").strings().toSet(),
        questions = questions,
        autoResolutionMs = params.optNullableLong("autoResolutionMs"),
    )
}

private fun JSONObject.threadId(): String? =
    optString("threadId").takeIf(String::isNotBlank)
        ?: optJSONObject("thread")?.optString("id")?.takeIf(String::isNotBlank)

private fun Any?.toCodexRpcIdOrNull(): CodexRpcId? =
    when (this) {
        is Byte, is Short, is Int, is Long -> CodexRpcId.NumberValue((this as Number).toLong())
        is String -> CodexRpcId.StringValue(this)
        else -> null
    }

private fun List<CodexTurn>.upsertItem(turnId: String, item: CodexTimelineItem): List<CodexTurn> {
    val existing = firstOrNull { it.id == turnId }
    if (existing == null) {
        return this + CodexTurn(id = turnId, status = CodexTurnStatus.IN_PROGRESS, items = listOf(item))
    }
    return map { turn ->
        if (turn.id != turnId) turn
        else turn.copy(items = turn.items.filterNot { it.id == item.id } + item)
    }
}

private fun JSONObject.epochSeconds(vararg names: String): Long {
    names.forEach { name ->
        if (!has(name) || isNull(name)) return@forEach
        val numeric = optLong(name)
        if (numeric > 0L) return if (numeric > 10_000_000_000L) numeric / 1_000L else numeric
    }
    return 0L
}

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optNullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name)

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
        optString(index).takeIf(String::isNotBlank)
    }

private fun Any?.wireText(): String =
    when (this) {
        null, JSONObject.NULL -> ""
        is String -> this
        is JSONArray -> (0 until length()).joinToString(" ") { opt(it).wireText() }
        is JSONObject -> toString()
        else -> toString()
    }
