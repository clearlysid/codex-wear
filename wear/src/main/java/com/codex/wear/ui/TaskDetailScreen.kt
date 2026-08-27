package com.codex.wear.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import coil3.compose.AsyncImage
import com.codex.wear.viewmodel.TaskDetailUiState
import com.codex.wear.viewmodel.TaskStatusUi
import com.codex.wear.viewmodel.TimelineItemUi

@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    onApprove: (String) -> Unit,
    onDecline: (String) -> Unit,
    onStop: () -> Unit,
    onRetryItem: (String) -> Unit,
    onReply: () -> Unit,
    onImageClick: (String) -> Unit,
    onRetryConnection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    state.connectionError?.let { error ->
        ConnectionRecoveryScreen(error, onRetryConnection, onOpenSettings)
        return
    }

    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var hasPositionedAtLatest by remember(state.taskId) { mutableStateOf(false) }

    LaunchedEffect(state.taskId, state.isLoading, state.timeline.size) {
        if (!hasPositionedAtLatest && !state.isLoading && state.timeline.isNotEmpty()) {
            // The header occupies index 0, so the final timeline item is one position later.
            listState.scrollToItem(state.timeline.size)
            hasPositionedAtLatest = true
        }
    }

    AppScaffold {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                when (state.status) {
                    TaskStatusUi.WORKING -> EdgeButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop task")
                    }
                    TaskStatusUi.IDLE, TaskStatusUi.COMPLETE, TaskStatusUi.STOPPED ->
                        EdgeButton(onClick = onReply) {
                            Icon(Icons.Filled.Mic, contentDescription = "Reply with voice")
                        }
                    else -> Unit
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "task-header", contentType = "header") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                            .transformedHeight(this, transformationSpec),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(state.projectName, state.status.name.lowercase().replace('_', ' '))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.isLoading && state.timeline.isEmpty()) {
                    item(key = "loading", contentType = "status") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                                .transformedHeight(this, transformationSpec),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }

                state.timeline.forEach { item ->
                    item(key = item.id, contentType = item::class.simpleName) {
                        TimelineItem(
                            item = item,
                            onApprove = { onApprove(item.id) },
                            onDecline = { onDecline(item.id) },
                            onRetry = { onRetryItem(item.id) },
                            onImageClick = onImageClick,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(
    item: TimelineItemUi,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onImageClick: (String) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    if (item is TimelineItemUi.UserMessage) {
        Card(
            onClick = {},
            modifier = modifier,
            transformation = transformation,
            colors = CardDefaults.cardColors(
                containerColor = UserMessageBackground,
                contentColor = Color.White,
            ),
        ) {
            if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodySmall)
            item.imageUrls.forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(92.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onImageClick(url) },
                )
            }
        }
        return
    }

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (item) {
            is TimelineItemUi.CodexMessage -> {
                Text(item.text, style = MaterialTheme.typography.bodySmall)
                if (item.isStreaming) CircularProgressIndicator(modifier = Modifier.size(12.dp))
            }
            is TimelineItemUi.ToolActivity -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.isRunning) CircularProgressIndicator(modifier = Modifier.size(10.dp))
                }
                item.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 3,
                    )
                }
            }
            is TimelineItemUi.FileChanges -> {
                Text(
                    "${item.files.size} file${if (item.files.size == 1) "" else "s"} changed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
                item.files.take(4).forEach { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            is TimelineItemUi.Approval -> {
                Text(
                    "Approval needed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(item.prompt, style = MaterialTheme.typography.bodySmall)
                item.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                if (item.isResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else if (item.canRespond) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        FilledIconButton(onClick = onDecline) {
                            Icon(Icons.Filled.Close, contentDescription = "Decline")
                        }
                        FilledIconButton(onClick = onApprove) {
                            Icon(Icons.Filled.Check, contentDescription = "Approve")
                        }
                    }
                } else {
                    Text("Open the originating client to respond", style = MaterialTheme.typography.bodyExtraSmall)
                }
            }
            is TimelineItemUi.Error -> {
                Text("Task error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(item.message, style = MaterialTheme.typography.bodySmall)
                if (item.canRetry) {
                    FilledIconButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                }
            }
            is TimelineItemUi.UserMessage -> Unit
        }
    }
}

private val UserMessageBackground = Color(0xFF252528)
