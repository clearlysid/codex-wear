package com.sidekick.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.sidekick.watch.viewmodel.ConversationSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    conversations: List<ConversationSummary>,
    activeConversationId: String?,
    onNewConversationWithKeyboard: () -> Unit,
    onNewConversationWithVoice: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    loadMoreIncrement: Int,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val safeIncrement = loadMoreIncrement.coerceAtLeast(1)
    var visibleConversationCount by rememberSaveable { mutableIntStateOf(safeIncrement) }

    LaunchedEffect(conversations.size, safeIncrement) {
        visibleConversationCount =
            when {
                conversations.isEmpty() -> 0
                visibleConversationCount <= 0 -> minOf(safeIncrement, conversations.size)
                else -> visibleConversationCount.coerceAtMost(conversations.size)
            }
    }

    val canShowMore = visibleConversationCount < conversations.size

    AppScaffold {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                if (canShowMore) {
                    EdgeButton(
                        onClick = {
                            visibleConversationCount =
                                (visibleConversationCount + safeIncrement).coerceAtMost(conversations.size)
                        },
                    ) {
                        Text("Show more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp)
                                .transformedHeight(this, transformationSpec),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledIconButton(
                            onClick = onNewConversationWithKeyboard,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = KeyboardActionGreen,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Keyboard,
                                contentDescription = "New conversation (keyboard)",
                            )
                        }
                        FilledIconButton(
                            onClick = onNewConversationWithVoice,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MicActionPeach,
                                contentColor = MicActionContent,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "New conversation (voice)",
                            )
                        }
                    }
                }

                conversations.take(visibleConversationCount).forEach { conversation ->
                    item(key = conversation.id) {
                        SwipeToReveal(
                            primaryAction = {
                                PrimaryActionButton(
                                    onClick = { onDeleteConversation(conversation.id) },
                                    icon = {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    },
                                    text = { Text("Delete") },
                                )
                            },
                            onSwipePrimaryAction = { onDeleteConversation(conversation.id) },
                        ) {
                            Card(
                                onClick = { onOpenConversation(conversation.id) },
                                modifier = Modifier.fillMaxWidth(),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conversation.displayTitle(42),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = formatLastUpdated(conversation.lastUpdatedEpochMs),
                                            style = MaterialTheme.typography.bodyExtraSmall,
                                        )
                                    }
                                    if (conversation.id == activeConversationId) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLastUpdated(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
    return formatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
