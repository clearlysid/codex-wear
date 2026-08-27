package com.sidekick.watch.ui

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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.sidekick.watch.viewmodel.TaskDetailUiState
import com.sidekick.watch.viewmodel.TaskStatusUi
import com.sidekick.watch.viewmodel.TimelineItemUi

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
                        TimelineCard(
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
private fun TimelineCard(
    item: TimelineItemUi,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onImageClick: (String) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    val colors =
        when (item) {
            is TimelineItemUi.UserMessage -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            is TimelineItemUi.Approval, is TimelineItemUi.Error -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            else -> CardDefaults.cardColors()
        }
    Card(
        onClick = {},
        enabled = false,
        modifier = modifier,
        transformation = transformation,
        colors = colors,
    ) {
        when (item) {
            is TimelineItemUi.UserMessage -> {
                Text("You", style = MaterialTheme.typography.labelSmall)
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
            is TimelineItemUi.CodexMessage -> {
                Text("Codex", style = MaterialTheme.typography.labelSmall)
                Text(item.text, style = MaterialTheme.typography.bodySmall)
                if (item.isStreaming) CircularProgressIndicator(modifier = Modifier.size(14.dp))
            }
            is TimelineItemUi.ToolActivity -> {
                TimelineHeading(Icons.Filled.Terminal, item.title)
                item.summary?.let { Text(it, style = MaterialTheme.typography.bodyExtraSmall, maxLines = 3) }
                if (item.isRunning) CircularProgressIndicator(modifier = Modifier.size(14.dp))
            }
            is TimelineItemUi.FileChanges -> {
                TimelineHeading(Icons.Filled.Description, "${item.files.size} file${if (item.files.size == 1) "" else "s"} changed")
                item.summary?.let { Text(it, style = MaterialTheme.typography.bodyExtraSmall) }
                item.files.take(4).forEach { path ->
                    Text(path, style = MaterialTheme.typography.bodyExtraSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is TimelineItemUi.Approval -> {
                Text("Approval needed", style = MaterialTheme.typography.labelSmall)
                Text(item.prompt, style = MaterialTheme.typography.bodySmall)
                item.detail?.let { Text(it, style = MaterialTheme.typography.bodyExtraSmall, maxLines = 3) }
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
                TimelineHeading(Icons.Filled.Error, "Task error")
                Text(item.message, style = MaterialTheme.typography.bodySmall)
                if (item.canRetry) {
                    FilledIconButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
