package com.sidekick.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.sidekick.watch.viewmodel.HomeUiState

@Composable
fun CodexHomeScreen(
    state: HomeUiState,
    onAskCodex: () -> Unit,
    onTaskClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAllTasksClick: () -> Unit,
    onRetryConnection: () -> Unit,
) {
    state.connectionError?.let { error ->
        ConnectionRecoveryScreen(error, onRetryConnection, onSettingsClick)
        return
    }

    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    AppScaffold {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "header", contentType = "header") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                            .transformedHeight(this, transformationSpec),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Codex", style = MaterialTheme.typography.titleMedium)
                    }
                }

                item(key = "ask", contentType = "action") {
                    Card(
                        onClick = onAskCodex,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Text("Ask Codex", style = MaterialTheme.typography.titleSmall)
                    }
                }

                sectionLabel("activity-label", "Activity", transformationSpec)
                if (state.isLoading && state.activity.isEmpty()) {
                    item(key = "loading", contentType = "status") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                                .transformedHeight(this, transformationSpec),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.activity.isEmpty()) {
                    emptyRow("activity-empty", "Nothing needs attention", transformationSpec)
                } else {
                    state.activity.forEach { task ->
                        item(key = "activity-${task.id}", contentType = "task") {
                            Card(
                                onClick = { onTaskClick(task.id) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) { TaskSummaryContent(task) }
                        }
                    }
                }

                sectionLabel("today-label", "Today", transformationSpec)
                if (state.today.isEmpty()) {
                    emptyRow("today-empty", "No completed tasks today", transformationSpec)
                } else {
                    state.today.forEach { task ->
                        item(key = "today-${task.id}", contentType = "task") {
                            Card(
                                onClick = { onTaskClick(task.id) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) { TaskSummaryContent(task) }
                        }
                    }
                }

                item(key = "settings", contentType = "navigation") {
                    Card(
                        onClick = onSettingsClick,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Text("Settings")
                    }
                }
                item(key = "all-tasks", contentType = "navigation") {
                    Card(
                        onClick = onAllTasksClick,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Text("All Tasks")
                    }
                }
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.sectionLabel(
    key: String,
    label: String,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
) {
    item(key = key, contentType = "section") {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                .transformedHeight(this, transformationSpec),
        )
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.emptyRow(
    key: String,
    label: String,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
) {
    item(key = key, contentType = "empty") {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                .transformedHeight(this, transformationSpec),
        )
    }
}
