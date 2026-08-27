package com.codex.wear.viewmodel

import com.codex.wear.data.codex.CodexAgentMessagePhase
import com.codex.wear.data.codex.CodexTaskDetail
import com.codex.wear.data.codex.CodexTaskSummary
import com.codex.wear.data.codex.CodexTimelineItem
import com.codex.wear.data.codex.CodexTimelineItemStatus
import com.codex.wear.data.codex.CodexTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexUiMapperTest {
    @Test
    fun `task detail omits reasoning but keeps tools and final replies`() {
        val detail =
            CodexTaskDetail(
                summary = CodexTaskSummary(id = "task"),
                turns =
                    listOf(
                        CodexTurn(
                            id = "turn",
                            items =
                                listOf(
                                    CodexTimelineItem.UserMessage(id = "user", text = "Please update it"),
                                    CodexTimelineItem.Unknown(id = "reasoning", rawType = "reasoning"),
                                    CodexTimelineItem.ToolCall(
                                        id = "tool",
                                        tool = "Read file",
                                        status = CodexTimelineItemStatus.COMPLETED,
                                    ),
                                    CodexTimelineItem.AgentMessage(
                                        id = "reply",
                                        text = "Done",
                                        phase = CodexAgentMessagePhase.FINAL_ANSWER,
                                    ),
                                ),
                        ),
                    ),
            )

        val timeline = detail.toUi().timeline

        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is TimelineItemUi.UserMessage)
        assertTrue(timeline[1] is TimelineItemUi.ToolActivity)
        assertTrue(timeline[2] is TimelineItemUi.CodexMessage)
        assertFalse(timeline.filterIsInstance<TimelineItemUi.ToolActivity>().any { it.title.contains("reasoning", true) })
    }
}
