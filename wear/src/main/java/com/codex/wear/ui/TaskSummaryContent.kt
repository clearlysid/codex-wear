package com.codex.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.codex.wear.viewmodel.TaskStatusUi
import com.codex.wear.viewmodel.TaskSummaryUi
import java.time.Duration
import java.time.Instant

@Composable
fun TaskSummaryContent(task: TaskSummaryUi) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = task.title.ifBlank { "Untitled task" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val metadata = listOfNotNull(task.projectName?.takeIf(String::isNotBlank), relativeTime(task.updatedAtEpochMs))
        Text(
            text = metadata.joinToString(" · "),
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun TaskStatusUi.label(): String =
    when (this) {
        TaskStatusUi.IDLE -> "Idle"
        TaskStatusUi.WORKING -> "Working"
        TaskStatusUi.NEEDS_ATTENTION -> "Needs attention"
        TaskStatusUi.COMPLETE -> "Complete"
        TaskStatusUi.FAILED -> "Failed"
        TaskStatusUi.STOPPED -> "Stopped"
    }

internal fun TaskStatusUi.cardContainerColor(): Color =
    when (this) {
        TaskStatusUi.IDLE, TaskStatusUi.STOPPED -> Color(0xFF303033)
        TaskStatusUi.WORKING -> Color(0xFF243746)
        TaskStatusUi.NEEDS_ATTENTION, TaskStatusUi.FAILED -> Color(0xFF5A421C)
        TaskStatusUi.COMPLETE -> Color(0xFF263B31)
    }

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val duration = Duration.between(Instant.ofEpochMilli(epochMs), Instant.now())
    return when {
        duration.isNegative || duration.seconds < 60 -> "now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m"
        duration.toHours() < 24 -> "${duration.toHours()}h"
        else -> "${duration.toDays()}d"
    }
}
