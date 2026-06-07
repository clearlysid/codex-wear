package com.sidekick.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.sidekick.watch.viewmodel.ChatUiState
import com.sidekick.watch.viewmodel.MessageRole

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    conversationTitle: String,
    onOpenTextInput: () -> Unit,
    onImageClick: (String) -> Unit = {},
    onOpenChats: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val haptic = LocalHapticFeedback.current
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    var swipeOffset by remember { mutableStateOf(0f) }
    var wasPolling by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isPolling) {
        if (wasPolling && !uiState.isPolling) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasPolling = uiState.isPolling
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onOpenChats, swipeThresholdPx) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeOffset = 0f },
                    onHorizontalDrag = { _, dragAmount -> swipeOffset += dragAmount },
                    onDragEnd = {
                        if (swipeOffset < -swipeThresholdPx) onOpenChats()
                        swipeOffset = 0f
                    },
                    onDragCancel = { swipeOffset = 0f },
                )
            },
    ) {
        AppScaffold {
            ScreenScaffold(
                scrollState = listState,
                edgeButton = {
                    EdgeButton(onClick = onOpenTextInput) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Compose",
                        )
                    }
                },
            ) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = conversationTitle,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }

                    uiState.messages.forEach { message ->
                        item {
                            if (message.role == MessageRole.USER) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainer,
                                                RoundedCornerShape(16.dp),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            } else {
                                val segments = remember(message.text) { parseMessageContent(message.text) }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    segments.forEach { segment ->
                                        when (segment) {
                                            is MessageSegment.Text -> Text(
                                                text = segment.content,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            is MessageSegment.Image -> AsyncImage(
                                                model = segment.url,
                                                contentDescription = segment.altText,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 120.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onImageClick(segment.url) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.isSending || uiState.isPolling) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    uiState.errorMessage?.let { error ->
                        item {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
