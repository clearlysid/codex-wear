package com.sidekick.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.sidekick.watch.viewmodel.AllTasksUiState

@Composable
fun AllTasksScreen(
    state: AllTasksUiState,
    onTaskClick: (String) -> Unit,
    onRetryConnection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    state.connectionError?.let { error ->
        ConnectionRecoveryScreen(error, onRetryConnection, onOpenSettings)
        return
    }

    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val isEmpty = state.recent.isEmpty() && state.projectSections.all { it.tasks.isEmpty() }

    AppScaffold {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "title", contentType = "header") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .transformedHeight(this, transformationSpec),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("All Tasks", style = MaterialTheme.typography.titleSmall)
                    }
                }
                when {
                    state.isLoading && isEmpty -> item(key = "loading", contentType = "status") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                                .transformedHeight(this, transformationSpec),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    isEmpty -> item(key = "empty", contentType = "empty") {
                        Text(
                            "No tasks in the last seven days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                                .transformedHeight(this, transformationSpec),
                        )
                    }
                    else -> {
                        if (state.recent.isNotEmpty()) {
                            allTasksSectionLabel("Recent", "recent-label", transformationSpec)
                            state.recent.forEach { task ->
                                item(key = "recent-${task.id}", contentType = "task") {
                                    Card(
                                        onClick = { onTaskClick(task.id) },
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                        transformation = SurfaceTransformation(transformationSpec),
                                    ) { TaskSummaryContent(task) }
                                }
                            }
                        }
                        state.projectSections.forEachIndexed { sectionIndex, section ->
                            allTasksSectionLabel(section.title, "section-$sectionIndex", transformationSpec)
                            section.tasks.forEach { task ->
                                item(key = "section-$sectionIndex-${task.id}", contentType = "task") {
                                    Card(
                                        onClick = { onTaskClick(task.id) },
                                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                        transformation = SurfaceTransformation(transformationSpec),
                                    ) { TaskSummaryContent(task) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.allTasksSectionLabel(
    label: String,
    key: String,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
) {
    item(key = key, contentType = "section") {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
                .transformedHeight(this, transformationSpec),
        )
    }
}
