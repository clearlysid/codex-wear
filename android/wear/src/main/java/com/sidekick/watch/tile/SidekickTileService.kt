package com.sidekick.watch.tile

import android.annotation.SuppressLint
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.PrimaryLayoutMargins
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.sidekick.watch.data.codex.CodexTaskSummary
import com.sidekick.watch.data.codex.TaskSnapshotStore
import com.sidekick.watch.domain.TileDisplayState
import com.sidekick.watch.domain.TileTaskSelection
import com.sidekick.watch.domain.selectTileTasks
import com.sidekick.watch.presentation.TileEntryActivity

@SuppressLint("UnsafeOptInUsageError")
class SidekickTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val tasks = runCatching { TaskSnapshotStore(applicationContext).load().tasks }.getOrDefault(emptyList())
        val selection = selectTileTasks(tasks = tasks)

        val layout =
            primaryLayout(
                titleSlot = { text(LayoutString("Codex")) },
                mainSlot = { monitorContent(selection) },
                bottomSlot = {
                    if (selection.state == TileDisplayState.IDLE) {
                        textEdgeButton(
                            onClick = buildClickable("ask_codex", INPUT_MODE_VOICE),
                            colors = buttonColors(ASK_BUTTON_BG, ASK_BUTTON_TEXT),
                        ) {
                            text(LayoutString("Ask Codex"))
                        }
                    } else {
                        text(LayoutString(selection.totalCountLabel()))
                    }
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
                .addContent(stateHeader(selection.state))

        selection.visibleTasks.forEachIndexed { index, task ->
            column.addContent(verticalSpacer(if (index == 0) 5f else 3f))
            column.addContent(taskRow(task = task, rowIndex = index))
        }

        return column.build()
    }

    private fun MaterialScope.stateHeader(
        state: TileDisplayState,
    ): LayoutElementBuilders.LayoutElement {
        val palette = state.palette()
        return LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(stateBadge(palette))
            .addContent(horizontalSpacer(7f))
            .addContent(
                labelText(
                    value = state.label(),
                    sizeSp = 14f,
                    color = WHITE,
                    weight = LayoutElementBuilders.FONT_WEIGHT_MEDIUM,
                ),
            )
            .build()
    }

    private fun stateBadge(palette: StatePalette): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(22f))
            .setHeight(DimensionBuilders.dp(22f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(palette.background))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(11f))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                labelText(
                    value = palette.symbol,
                    sizeSp = 13f,
                    color = palette.foreground,
                    weight = LayoutElementBuilders.FONT_WEIGHT_BOLD,
                ),
            )
            .build()

    private fun taskRow(
        task: CodexTaskSummary,
        rowIndex: Int,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.dp(34f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        buildClickable(
                            clickId = "open_task_$rowIndex",
                            inputMode = INPUT_MODE_TASK,
                            taskId = task.id,
                        ),
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(TASK_ROW_BG))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(16f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(11f))
                            .setEnd(DimensionBuilders.dp(11f))
                            .setTop(DimensionBuilders.dp(6f))
                            .setBottom(DimensionBuilders.dp(6f))
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
                LayoutElementBuilders.Text.Builder()
                    .setText(task.displayTitle.singleLine().take(MAX_TASK_TITLE_CHARS))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(12f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(ColorBuilders.argb(WHITE))
                            .build(),
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

    private fun horizontalSpacer(widthDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.dp(widthDp))
            .build()

    private fun buildClickable(
        clickId: String,
        inputMode: String,
        taskId: String? = null,
    ): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(buildLaunchAction(inputMode, taskId))
            .build()

    private fun buildLaunchAction(
        inputMode: String,
        taskId: String? = null,
    ): androidx.wear.protolayout.ActionBuilders.LaunchAction {
        val activity =
            androidx.wear.protolayout.ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName(TileEntryActivity::class.java.name)
                .addKeyToExtraMapping(
                    EXTRA_INPUT_MODE,
                    androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                        .setValue(inputMode)
                        .build(),
                )

        if (!taskId.isNullOrBlank()) {
            val taskExtra =
                androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                    .setValue(taskId)
                    .build()
            activity.addKeyToExtraMapping(EXTRA_TASK_ID, taskExtra)
            activity.addKeyToExtraMapping(
                LEGACY_CONVERSATION_ID,
                androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder()
                    .setValue(taskId)
                    .build(),
            )
        }

        return androidx.wear.protolayout.ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(activity.build())
            .build()
    }

    private fun buttonColors(containerArgb: Int, textArgb: Int): ButtonColors =
        ButtonColors(
            LayoutColor(containerArgb),
            LayoutColor(textArgb),
            LayoutColor(textArgb),
            LayoutColor(textArgb),
        )

    private fun TileDisplayState.label(): String =
        when (this) {
            TileDisplayState.IDLE -> "Idle"
            TileDisplayState.WORKING -> "Working"
            TileDisplayState.NEEDS_ATTENTION -> "Needs attention"
            TileDisplayState.COMPLETE -> "Complete"
        }

    private fun TileDisplayState.palette(): StatePalette =
        when (this) {
            TileDisplayState.IDLE -> StatePalette(IDLE_BG, IDLE_FG, "○")
            TileDisplayState.WORKING -> StatePalette(WORKING_BG, WORKING_FG, "●")
            TileDisplayState.NEEDS_ATTENTION -> StatePalette(ATTENTION_BG, ATTENTION_FG, "!")
            TileDisplayState.COMPLETE -> StatePalette(COMPLETE_BG, COMPLETE_FG, "✓")
        }

    private fun TileTaskSelection.totalCountLabel(): String =
        when (state) {
            TileDisplayState.WORKING -> countLabel("working")
            TileDisplayState.NEEDS_ATTENTION ->
                if (totalCount == 1) "1 needs attention" else "$totalCount need attention"
            TileDisplayState.COMPLETE -> countLabel("complete")
            TileDisplayState.IDLE -> "No active tasks"
        }

    private fun TileTaskSelection.countLabel(label: String): String =
        if (totalCount == 1) "1 task $label" else "$totalCount tasks $label"

    private fun String.singleLine(): String = replace(WHITESPACE, " ").trim()

    private data class StatePalette(
        val background: Int,
        val foreground: Int,
        val symbol: String,
    )

    companion object {
        const val EXTRA_INPUT_MODE = "input_mode"
        const val EXTRA_TASK_ID = "task_id"

        private const val LEGACY_CONVERSATION_ID = "conversation_id"

        private const val INPUT_MODE_VOICE = "voice"

        /** Legacy route value understood by MainActivity; semantically this now opens a task. */
        private const val INPUT_MODE_TASK = "activity"

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ASK_BUTTON_BG = 0xFFE8B88E.toInt()
        private const val ASK_BUTTON_TEXT = 0xFF2A160E.toInt()
        private const val TASK_ROW_BG = 0xFF202024.toInt()
        private const val IDLE_BG = 0xFF34343A.toInt()
        private const val IDLE_FG = 0xFFD4D4D8.toInt()
        private const val WORKING_BG = 0xFF173F5F.toInt()
        private const val WORKING_FG = 0xFF9BD4FF.toInt()
        private const val ATTENTION_BG = 0xFF5B3215.toInt()
        private const val ATTENTION_FG = 0xFFFFC58F.toInt()
        private const val COMPLETE_BG = 0xFF173D2B.toInt()
        private const val COMPLETE_FG = 0xFF8CE5B3.toInt()
        private const val MAX_TASK_TITLE_CHARS = 44
        private val WHITESPACE = Regex("\\s+")
    }
}
