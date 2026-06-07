package com.sidekick.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
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
    onOpenVoiceInput: () -> Unit,
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
                        if (swipeOffset > swipeThresholdPx) onOpenChats()
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
                    EdgeButton(onClick = {}) {
                        Row(
                            modifier = Modifier
                                .width(88.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(KeyboardActionGreen)
                                    .clickable(onClick = onOpenTextInput),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Keyboard,
                                    contentDescription = "Text reply",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MicActionPeach)
                                    .clickable(onClick = onOpenVoiceInput),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = "Voice reply",
                                    modifier = Modifier.size(20.dp),
                                    tint = MicActionContent,
                                )
                            }
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
                        item(key = message.id) {
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
                                                text = remember(segment.content) { markdownInlineText(segment.content) },
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

private fun markdownInlineText(text: String) = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val boldStar = text.indexOf("**", index).takeIf { it >= 0 }
        val boldUnderscore = text.indexOf("__", index).takeIf { it >= 0 }
        val italicStar = text.indexOf("*", index).takeIf { it >= 0 }
        val italicUnderscore = text.indexOf("_", index).takeIf { it >= 0 }
        val next = listOfNotNull(
            boldStar?.let { MarkdownMarker(it, "**", FontWeight.Bold, null) },
            boldUnderscore?.let { MarkdownMarker(it, "__", FontWeight.Bold, null) },
            italicStar?.let { MarkdownMarker(it, "*", null, FontStyle.Italic) },
            italicUnderscore?.let { MarkdownMarker(it, "_", null, FontStyle.Italic) },
        ).minByOrNull { it.index }

        if (next == null) {
            append(text.substring(index))
            break
        }

        if (next.index > index) append(text.substring(index, next.index))
        val contentStart = next.index + next.marker.length
        val end = text.indexOf(next.marker, contentStart)
        if (end < 0) {
            append(next.marker)
            index = contentStart
            continue
        }

        val content = text.substring(contentStart, end)
        pushStyle(
            SpanStyle(
                fontWeight = next.fontWeight,
                fontStyle = next.fontStyle,
            ),
        )
        append(content)
        pop()
        index = end + next.marker.length
    }
}

private data class MarkdownMarker(
    val index: Int,
    val marker: String,
    val fontWeight: FontWeight?,
    val fontStyle: FontStyle?,
)
