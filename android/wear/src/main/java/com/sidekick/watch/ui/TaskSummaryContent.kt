package com.sidekick.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.sidekick.watch.viewmodel.TaskStatusUi
import com.sidekick.watch.viewmodel.TaskSummaryUi
import java.time.Duration
import java.time.Instant

@Composable
fun TaskSummaryContent(task: TaskSummaryUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = task.status.icon(),
            contentDescription = task.status.label(),
            tint = task.status.color(),
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
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

@Composable
private fun TaskStatusUi.color(): Color =
    when (this) {
        TaskStatusUi.NEEDS_ATTENTION, TaskStatusUi.FAILED -> MaterialTheme.colorScheme.error
        TaskStatusUi.COMPLETE -> MaterialTheme.colorScheme.primary
        TaskStatusUi.WORKING -> MicActionPeach
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun TaskStatusUi.icon() =
    when (this) {
        TaskStatusUi.IDLE -> Icons.Filled.PauseCircle
        TaskStatusUi.WORKING -> Icons.Filled.Sync
        TaskStatusUi.NEEDS_ATTENTION -> Icons.Filled.PriorityHigh
        TaskStatusUi.COMPLETE -> Icons.Filled.CheckCircle
        TaskStatusUi.FAILED -> Icons.Filled.Error
        TaskStatusUi.STOPPED -> Icons.Filled.HourglassEmpty
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
