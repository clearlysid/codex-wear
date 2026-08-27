package com.sidekick.watch.domain

import com.sidekick.watch.data.codex.CodexProject
import com.sidekick.watch.data.codex.CodexTaskState
import com.sidekick.watch.data.codex.CodexTaskSummary
import java.time.Instant
import java.time.ZoneId

const val TASK_LOOKBACK_SECONDS = 7L * 24L * 60L * 60L
const val ALL_TASKS_RECENT_LIMIT = 3
const val HOME_ACTIVITY_LIMIT = 3
const val HOME_TODAY_LIMIT = 3
const val TILE_TASK_LIMIT = 2
const val RECENT_PROJECT_LIMIT = 5

data class HomeTaskSections(
    val activity: List<CodexTaskSummary>,
    val today: List<CodexTaskSummary>,
)

data class ProjectTaskGroup(
    /** Null is the explicit No project group. */
    val project: CodexProject?,
    val tasks: List<CodexTaskSummary>,
)

data class AllTasksOrganization(
    val recent: List<CodexTaskSummary>,
    val projectGroups: List<ProjectTaskGroup>,
)

enum class TileDisplayState {
    IDLE,
    WORKING,
    NEEDS_ATTENTION,
    COMPLETE,
}

data class TileTaskSelection(
    val state: TileDisplayState,
    val visibleTasks: List<CodexTaskSummary>,
    /** Number of tasks represented by [state], including rows that do not fit on the Tile. */
    val totalCount: Int,
    val isFocused: Boolean,
)

/**
 * Builds the bounded Home sections. Pending actions and errors lead, followed by working tasks and
 * unread completions. A completed task moves to Today once it has been read.
 */
fun organizeHomeTasks(
    tasks: List<CodexTaskSummary>,
    nowEpochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    activityLimit: Int = HOME_ACTIVITY_LIMIT,
    todayLimit: Int = HOME_TODAY_LIMIT,
): HomeTaskSections {
    require(activityLimit >= 0) { "activityLimit must not be negative" }
    require(todayLimit >= 0) { "todayLimit must not be negative" }

    val uniqueTasks = deduplicateTasks(tasks)
    val activity =
        uniqueTasks
            .filter(::belongsInHomeActivity)
            .sortedWith(homeActivityComparator)
            .take(activityLimit)

    val today =
        uniqueTasks
            .asSequence()
            .filter { it.state == CodexTaskState.COMPLETE && !it.isUnread }
            .filter { isSameLocalDate(it.activityAtEpochSeconds, nowEpochSeconds, zoneId) }
            .sortedWith(newestTaskComparator)
            .take(todayLimit)
            .toList()

    return HomeTaskSections(activity = activity, today = today)
}

/**
 * Builds All Tasks from the rolling previous seven days. The top three Recent tasks are omitted
 * from the project groups so every task appears exactly once.
 */
fun organizeAllTasks(
    tasks: List<CodexTaskSummary>,
    nowEpochSeconds: Long,
    lookbackSeconds: Long = TASK_LOOKBACK_SECONDS,
    recentLimit: Int = ALL_TASKS_RECENT_LIMIT,
): AllTasksOrganization {
    require(lookbackSeconds >= 0) { "lookbackSeconds must not be negative" }
    require(recentLimit >= 0) { "recentLimit must not be negative" }

    val cutoffEpochSeconds = nowEpochSeconds - lookbackSeconds
    val eligible =
        deduplicateTasks(tasks)
            .filter { it.activityAtEpochSeconds >= cutoffEpochSeconds }
            .sortedWith(newestTaskComparator)
    val recent = eligible.take(recentLimit)
    val recentIds = recent.asSequence().map(CodexTaskSummary::id).toHashSet()

    val projectGroups =
        eligible
            .asSequence()
            .filterNot { it.id in recentIds }
            .groupBy { it.project?.id }
            .values
            .map { groupTasks ->
                val sortedTasks = groupTasks.sortedWith(newestTaskComparator)
                ProjectTaskGroup(
                    project = sortedTasks.firstOrNull()?.project,
                    tasks = sortedTasks,
                )
            }
            .filter { it.tasks.isNotEmpty() }
            .sortedWith(
                compareByDescending<ProjectTaskGroup> { it.tasks.first().activityAtEpochSeconds }
                    .thenBy { it.project?.id.orEmpty() },
            )

    return AllTasksOrganization(recent = recent, projectGroups = projectGroups)
}

/**
 * Selects the Tile's highest-priority state and at most two rows. Attention includes failed tasks,
 * because the four-state Tile has no separate failure state. Read completions do not keep the Tile
 * permanently in Complete.
 *
 * When [focusedTaskId] identifies a task in the winning state, that single task is shown. A lower
 * priority focus never hides a task that needs attention.
 */
fun selectTileTasks(
    tasks: List<CodexTaskSummary>,
    focusedTaskId: String? = null,
    maxVisibleTasks: Int = TILE_TASK_LIMIT,
): TileTaskSelection {
    require(maxVisibleTasks > 0) { "maxVisibleTasks must be positive" }

    val uniqueTasks = deduplicateTasks(tasks)
    val (state, candidates) =
        when {
            uniqueTasks.any(::needsTileAttention) ->
                TileDisplayState.NEEDS_ATTENTION to
                    uniqueTasks.filter(::needsTileAttention).sortedWith(attentionTaskComparator)

            uniqueTasks.any { it.state == CodexTaskState.WORKING } ->
                TileDisplayState.WORKING to
                    uniqueTasks.filter { it.state == CodexTaskState.WORKING }.sortedWith(newestTaskComparator)

            uniqueTasks.any { it.state == CodexTaskState.COMPLETE && it.isUnread } ->
                TileDisplayState.COMPLETE to
                    uniqueTasks
                        .filter { it.state == CodexTaskState.COMPLETE && it.isUnread }
                        .sortedWith(newestTaskComparator)

            else -> TileDisplayState.IDLE to emptyList()
        }

    val focused = focusedTaskId?.let { id -> candidates.firstOrNull { it.id == id } }
    val visibleTasks = focused?.let(::listOf) ?: candidates.take(maxVisibleTasks)
    return TileTaskSelection(
        state = state,
        visibleTasks = visibleTasks,
        totalCount = candidates.size,
        isFocused = focused != null,
    )
}

/** Returns up to five unique projects ordered by their most recently active task. */
fun selectRecentProjects(
    tasks: List<CodexTaskSummary>,
    limit: Int = RECENT_PROJECT_LIMIT,
): List<CodexProject> {
    require(limit >= 0) { "limit must not be negative" }
    return deduplicateTasks(tasks)
        .asSequence()
        .sortedWith(newestTaskComparator)
        .mapNotNull(CodexTaskSummary::project)
        .distinctBy(CodexProject::id)
        .take(limit)
        .toList()
}

private fun deduplicateTasks(tasks: List<CodexTaskSummary>): List<CodexTaskSummary> =
    tasks.sortedWith(newestTaskComparator).distinctBy(CodexTaskSummary::id)

private fun belongsInHomeActivity(task: CodexTaskSummary): Boolean =
    when (task.state) {
        CodexTaskState.NEEDS_ATTENTION,
        CodexTaskState.ERROR,
        CodexTaskState.WORKING,
        -> true

        CodexTaskState.COMPLETE -> task.isUnread
        CodexTaskState.IDLE -> false
    }

private fun needsTileAttention(task: CodexTaskSummary): Boolean =
    task.state == CodexTaskState.NEEDS_ATTENTION || task.state == CodexTaskState.ERROR

private val newestTaskComparator =
    compareByDescending<CodexTaskSummary>(CodexTaskSummary::activityAtEpochSeconds)
        .thenBy(CodexTaskSummary::id)

private val oldestTaskComparator =
    compareBy<CodexTaskSummary>(CodexTaskSummary::activityAtEpochSeconds)
        .thenBy(CodexTaskSummary::id)

private val attentionTaskComparator =
    compareBy<CodexTaskSummary> { if (it.state == CodexTaskState.NEEDS_ATTENTION) 0 else 1 }
        .then(oldestTaskComparator)

private val homeActivityComparator =
    compareBy<CodexTaskSummary>(::homeActivityPriority)
        .thenComparator { first, second ->
            if (
                first.state == CodexTaskState.NEEDS_ATTENTION &&
                second.state == CodexTaskState.NEEDS_ATTENTION
            ) {
                oldestTaskComparator.compare(first, second)
            } else {
                newestTaskComparator.compare(first, second)
            }
        }

private fun homeActivityPriority(task: CodexTaskSummary): Int =
    when (task.state) {
        CodexTaskState.NEEDS_ATTENTION -> 0
        CodexTaskState.ERROR -> 1
        CodexTaskState.WORKING -> 2
        CodexTaskState.COMPLETE -> 3
        CodexTaskState.IDLE -> 4
    }

private fun isSameLocalDate(
    firstEpochSeconds: Long,
    secondEpochSeconds: Long,
    zoneId: ZoneId,
): Boolean =
    Instant.ofEpochSecond(firstEpochSeconds).atZone(zoneId).toLocalDate() ==
        Instant.ofEpochSecond(secondEpochSeconds).atZone(zoneId).toLocalDate()
