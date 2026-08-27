package com.sidekick.watch.domain

import com.sidekick.watch.data.codex.CodexProject
import com.sidekick.watch.data.codex.CodexTaskState
import com.sidekick.watch.data.codex.CodexTaskSummary
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPresentationOrganizerTest {
    private val now = Instant.parse("2026-08-26T12:00:00Z").epochSecond
    private val alpha = CodexProject(id = "alpha", name = "Alpha")
    private val beta = CodexProject(id = "beta", name = "Beta")
    private val gamma = CodexProject(id = "gamma", name = "Gamma")

    @Test
    fun `all tasks uses inclusive seven-day cutoff and excludes recent from groups`() {
        val atBoundary = now - TASK_LOOKBACK_SECONDS
        val tasks =
            listOf(
                task("recent-a", hoursAgo = 1, project = alpha),
                task("recent-b", hoursAgo = 2, project = beta),
                task("recent-none", hoursAgo = 3),
                task("alpha-older", hoursAgo = 4, project = alpha),
                task("gamma-newer", hoursAgo = 5, project = gamma),
                task("beta-older", hoursAgo = 6, project = beta),
                taskAt("boundary", atBoundary, project = gamma),
                taskAt("too-old", atBoundary - 1, project = alpha),
            )

        val result = organizeAllTasks(tasks, nowEpochSeconds = now)

        assertEquals(listOf("recent-a", "recent-b", "recent-none"), result.recent.ids())
        assertEquals(listOf("alpha", "gamma", "beta"), result.projectGroups.map { it.project?.id })
        assertEquals(listOf("alpha-older"), result.projectGroups[0].tasks.ids())
        assertEquals(listOf("gamma-newer", "boundary"), result.projectGroups[1].tasks.ids())
        assertEquals(listOf("beta-older"), result.projectGroups[2].tasks.ids())

        val allIds = result.recent.ids() + result.projectGroups.flatMap { it.tasks.ids() }
        assertEquals(allIds.size, allIds.distinct().size)
        assertTrue("boundary" in allIds)
        assertFalse("too-old" in allIds)
    }

    @Test
    fun `project groups are ordered and populated newest first including no project`() {
        val result =
            organizeAllTasks(
                tasks =
                    listOf(
                        task("recent", hoursAgo = 1, project = gamma),
                        task("no-project-new", hoursAgo = 2),
                        task("alpha-new", hoursAgo = 3, project = alpha),
                        task("no-project-old", hoursAgo = 4),
                        task("alpha-old", hoursAgo = 5, project = alpha),
                    ),
                nowEpochSeconds = now,
                recentLimit = 1,
            )

        assertEquals(listOf(null, "alpha"), result.projectGroups.map { it.project?.id })
        assertEquals(listOf("no-project-new", "no-project-old"), result.projectGroups[0].tasks.ids())
        assertEquals(listOf("alpha-new", "alpha-old"), result.projectGroups[1].tasks.ids())
    }

    @Test
    fun `home prioritizes attention then errors working and unread completions`() {
        val tasks =
            listOf(
                task("attention-new", hoursAgo = 1, state = CodexTaskState.NEEDS_ATTENTION),
                task("attention-old", hoursAgo = 8, state = CodexTaskState.NEEDS_ATTENTION),
                task("error", hoursAgo = 2, state = CodexTaskState.ERROR),
                task("working", hoursAgo = 3, state = CodexTaskState.WORKING),
                task("ready", hoursAgo = 4, state = CodexTaskState.COMPLETE, unread = true),
                task("read-today", hoursAgo = 5, state = CodexTaskState.COMPLETE),
                task("read-yesterday", hoursAgo = 25, state = CodexTaskState.COMPLETE),
                task("idle", hoursAgo = 1, state = CodexTaskState.IDLE),
            )

        val result =
            organizeHomeTasks(
                tasks = tasks,
                nowEpochSeconds = now,
                zoneId = ZoneOffset.UTC,
                activityLimit = 10,
                todayLimit = 10,
            )

        assertEquals(
            listOf("attention-old", "attention-new", "error", "working", "ready"),
            result.activity.ids(),
        )
        assertEquals(listOf("read-today"), result.today.ids())
    }

    @Test
    fun `tile attention takes precedence and exposes at most two rows`() {
        val result =
            selectTileTasks(
                listOf(
                    task("working", hoursAgo = 1, state = CodexTaskState.WORKING),
                    task("attention-new", hoursAgo = 2, state = CodexTaskState.NEEDS_ATTENTION),
                    task("attention-old", hoursAgo = 8, state = CodexTaskState.NEEDS_ATTENTION),
                    task("error", hoursAgo = 3, state = CodexTaskState.ERROR),
                    task("complete", hoursAgo = 1, state = CodexTaskState.COMPLETE, unread = true),
                ),
            )

        assertEquals(TileDisplayState.NEEDS_ATTENTION, result.state)
        assertEquals(listOf("attention-old", "attention-new"), result.visibleTasks.ids())
        assertEquals(3, result.totalCount)
        assertFalse(result.isFocused)
    }

    @Test
    fun `tile focus shows one task but never overrides a higher priority state`() {
        val tasks =
            listOf(
                task("working", hoursAgo = 1, state = CodexTaskState.WORKING),
                task("attention", hoursAgo = 2, state = CodexTaskState.NEEDS_ATTENTION),
            )

        val lowerPriorityFocus = selectTileTasks(tasks, focusedTaskId = "working")
        assertEquals(TileDisplayState.NEEDS_ATTENTION, lowerPriorityFocus.state)
        assertEquals(listOf("attention"), lowerPriorityFocus.visibleTasks.ids())
        assertFalse(lowerPriorityFocus.isFocused)

        val attentionFocus = selectTileTasks(tasks, focusedTaskId = "attention")
        assertEquals(listOf("attention"), attentionFocus.visibleTasks.ids())
        assertTrue(attentionFocus.isFocused)
    }

    @Test
    fun `tile ignores read completions and becomes idle`() {
        val result =
            selectTileTasks(
                listOf(task("read", hoursAgo = 1, state = CodexTaskState.COMPLETE, unread = false)),
            )

        assertEquals(TileDisplayState.IDLE, result.state)
        assertTrue(result.visibleTasks.isEmpty())
        assertEquals(0, result.totalCount)
    }

    @Test
    fun `tile working takes precedence over unread completion`() {
        val result =
            selectTileTasks(
                listOf(
                    task("complete", hoursAgo = 1, state = CodexTaskState.COMPLETE, unread = true),
                    task("working-old", hoursAgo = 4, state = CodexTaskState.WORKING),
                    task("working-new", hoursAgo = 2, state = CodexTaskState.WORKING),
                ),
            )

        assertEquals(TileDisplayState.WORKING, result.state)
        assertEquals(listOf("working-new", "working-old"), result.visibleTasks.ids())
        assertEquals(2, result.totalCount)
    }

    @Test
    fun `recent projects are unique newest first and capped at five`() {
        val delta = CodexProject(id = "delta", name = "Delta")
        val epsilon = CodexProject(id = "epsilon", name = "Epsilon")
        val zeta = CodexProject(id = "zeta", name = "Zeta")
        val tasks =
            listOf(
                task("alpha-new", hoursAgo = 1, project = alpha),
                task("no-project", hoursAgo = 2),
                task("beta", hoursAgo = 3, project = beta),
                task("alpha-old", hoursAgo = 4, project = alpha),
                task("gamma", hoursAgo = 5, project = gamma),
                task("delta", hoursAgo = 6, project = delta),
                task("epsilon", hoursAgo = 7, project = epsilon),
                task("zeta", hoursAgo = 8, project = zeta),
            )

        assertEquals(
            listOf("alpha", "beta", "gamma", "delta", "epsilon"),
            selectRecentProjects(tasks).map(CodexProject::id),
        )
    }

    @Test
    fun `duplicate task ids keep the newest server snapshot`() {
        val stale = task("same", hoursAgo = 10, state = CodexTaskState.WORKING)
        val fresh = task("same", hoursAgo = 1, state = CodexTaskState.NEEDS_ATTENTION)

        val result = selectTileTasks(listOf(stale, fresh))

        assertEquals(TileDisplayState.NEEDS_ATTENTION, result.state)
        assertEquals(listOf(fresh), result.visibleTasks)
        assertEquals(1, result.totalCount)
    }

    private fun task(
        id: String,
        hoursAgo: Long,
        project: CodexProject? = null,
        state: CodexTaskState = CodexTaskState.COMPLETE,
        unread: Boolean = false,
    ): CodexTaskSummary =
        taskAt(
            id = id,
            epochSeconds = now - hoursAgo * 60L * 60L,
            project = project,
            state = state,
            unread = unread,
        )

    private fun taskAt(
        id: String,
        epochSeconds: Long,
        project: CodexProject? = null,
        state: CodexTaskState = CodexTaskState.COMPLETE,
        unread: Boolean = false,
    ): CodexTaskSummary =
        CodexTaskSummary(
            id = id,
            state = state,
            project = project,
            updatedAtEpochSeconds = epochSeconds,
            isUnread = unread,
        )

    private fun List<CodexTaskSummary>.ids(): List<String> = map(CodexTaskSummary::id)
}
