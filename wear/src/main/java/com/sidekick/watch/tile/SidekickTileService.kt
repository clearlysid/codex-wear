package com.sidekick.watch.tile

import android.annotation.SuppressLint
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.PrimaryLayoutMargins
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.sidekick.watch.BuildConfig
import com.sidekick.watch.data.codex.CodexProject
import com.sidekick.watch.data.codex.CodexTaskState
import com.sidekick.watch.data.codex.CodexTaskSummary
import com.sidekick.watch.data.codex.TaskCacheSnapshot
import com.sidekick.watch.data.codex.TaskSnapshotStore
import com.sidekick.watch.domain.TileTaskSelection
import com.sidekick.watch.domain.selectTileTasks
import com.sidekick.watch.presentation.TileEntryActivity

@SuppressLint("UnsafeOptInUsageError")
class SidekickTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val snapshot = if (BuildConfig.SCREENSHOT_MODE) {
            screenshotSnapshot()
        } else {
            runCatching { TaskSnapshotStore(applicationContext).load() }
                .getOrDefault(TaskCacheSnapshot())
        }
        val selection = selectTileTasks(tasks = snapshot.tasks)
        val layout =
            primaryLayout(
                mainSlot = { monitorContent(selection) },
                bottomSlot = {
                    labelText(
                        value = snapshot.usageRemainingPercent.usageLimitLabel(),
                        sizeSp = 13f,
                        color = WHITE,
                        weight = LayoutElementBuilders.FONT_WEIGHT_MEDIUM,
                    )
                },
                margins = PrimaryLayoutMargins.MIN_PRIMARY_LAYOUT_MARGIN,
            )

        return TileBuilders.Tile.Builder()
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private fun MaterialScope.monitorContent(
        selection: TileTaskSelection,
    ): LayoutElementBuilders.LayoutElement {
        val column =
            LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (selection.visibleTasks.isEmpty()) {
            column.addContent(
                labelText(
                    value = "No active tasks",
                    sizeSp = 14f,
                    color = MUTED_TEXT,
                    weight = LayoutElementBuilders.FONT_WEIGHT_MEDIUM,
                ),
            )
        }

        selection.visibleTasks.forEachIndexed { index, task ->
            if (index > 0) column.addContent(verticalSpacer(6f))
            column.addContent(taskRow(task = task, rowIndex = index))
        }

        return column.build()
    }

    private fun taskRow(
        task: CodexTaskSummary,
        rowIndex: Int,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.dp(52f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        buildClickable(
                            clickId = "open_task_$rowIndex",
                            taskId = task.id,
                        ),
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(task.tileCardColor()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(20f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(12f))
                            .setEnd(DimensionBuilders.dp(12f))
                            .setTop(DimensionBuilders.dp(7f))
                            .setBottom(DimensionBuilders.dp(7f))
                            .build(),
                    )
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription("Open task: ${task.displayTitle}")
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(
                        labelText(
                            value = task.displayTitle.singleLine().take(MAX_TASK_TITLE_CHARS),
                            sizeSp = 13f,
                            color = WHITE,
                            weight = LayoutElementBuilders.FONT_WEIGHT_MEDIUM,
                        ),
                    )
                    .addContent(verticalSpacer(2f))
                    .addContent(
                        labelText(
                            value = (task.project?.name ?: "No project").singleLine().take(MAX_PROJECT_CHARS),
                            sizeSp = 10f,
                            color = MUTED_TEXT,
                            weight = LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                        ),
                    )
                    .build(),
            )
            .build()

    private fun labelText(
        value: String,
        sizeSp: Float,
        color: Int,
        weight: Int,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(sizeSp))
                    .setWeight(weight)
                    .setColor(ColorBuilders.argb(color))
                    .build(),
            )
            .build()

    private fun verticalSpacer(heightDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(heightDp))
            .build()

    private fun buildClickable(
        clickId: String,
        taskId: String,
    ): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(buildLaunchAction(taskId))
            .build()

    private fun buildLaunchAction(
        taskId: String,
    ): androidx.wear.protolayout.ActionBuilders.LaunchAction {
        val activity =
            androidx.wear.protolayout.ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName(TileEntryActivity::class.java.name)
                .addKeyToExtraMapping(
                    EXTRA_INPUT_MODE,
                    androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                        .setValue(INPUT_MODE_TASK)
                        .build(),
                )
                .addKeyToExtraMapping(
                    EXTRA_TASK_ID,
                    androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                        .setValue(taskId)
                        .build(),
                )
                .addKeyToExtraMapping(
                    LEGACY_CONVERSATION_ID,
                    androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                        .setValue(taskId)
                        .build(),
                )

        return androidx.wear.protolayout.ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(activity.build())
            .build()
    }

    private fun Int?.usageLimitLabel(): String =
        this?.let { "${it.coerceIn(0, 100)}% limit left" } ?: "Usage unavailable"

    private fun CodexTaskSummary.tileCardColor(): Int =
        when (state) {
            CodexTaskState.NEEDS_ATTENTION, CodexTaskState.ERROR -> ATTENTION_ROW_BG
            CodexTaskState.WORKING -> WORKING_ROW_BG
            CodexTaskState.IDLE, CodexTaskState.COMPLETE -> TASK_ROW_BG
        }

    private fun String.singleLine(): String = replace(WHITESPACE, " ").trim()

    private fun screenshotSnapshot(): TaskCacheSnapshot {
        val now = System.currentTimeMillis() / 1_000L
        return TaskCacheSnapshot(
            tasks = listOf(
                CodexTaskSummary(
                    id = "website",
                    title = "Personal website refresh",
                    state = CodexTaskState.COMPLETE,
                    project = CodexProject("perleg", "perleg"),
                    updatedAtEpochSeconds = now,
                    isUnread = true,
                ),
                CodexTaskSummary(
                    id = "collats",
                    title = "TPF past collats",
                    state = CodexTaskState.COMPLETE,
                    project = CodexProject("unconf", "unconf"),
                    updatedAtEpochSeconds = now - 60L,
                    isUnread = true,
                ),
            ),
            usageRemainingPercent = 57,
        )
    }

    companion object {
        const val EXTRA_INPUT_MODE = "input_mode"
        const val EXTRA_TASK_ID = "task_id"

        private const val LEGACY_CONVERSATION_ID = "conversation_id"

        /** Legacy route value understood by MainActivity; semantically this now opens a task. */
        private const val INPUT_MODE_TASK = "activity"

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MUTED_TEXT = 0xFFB9B9BE.toInt()
        private const val TASK_ROW_BG = 0xFF303033.toInt()
        private const val WORKING_ROW_BG = 0xFF243746.toInt()
        private const val ATTENTION_ROW_BG = 0xFF5A421C.toInt()
        private const val MAX_TASK_TITLE_CHARS = 34
        private const val MAX_PROJECT_CHARS = 24
        private val WHITESPACE = Regex("\\s+")
    }
}
