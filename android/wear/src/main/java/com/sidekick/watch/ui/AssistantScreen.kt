package com.sidekick.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.sidekick.watch.viewmodel.AssistantPhaseUi
import com.sidekick.watch.viewmodel.AssistantUiState

@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onProjectStep: (Int) -> Unit,
    onRedo: () -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val transcriptScroll = rememberScrollState()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { event ->
                if (state.phase != AssistantPhaseUi.LISTENING || state.projects.size <= 1) {
                    return@onRotaryScrollEvent false
                }
                rotaryAccumulator += event.verticalScrollPixels
                val direction = when {
                    rotaryAccumulator >= ROTARY_STEP_THRESHOLD_PX -> 1
                    rotaryAccumulator <= -ROTARY_STEP_THRESHOLD_PX -> -1
                    else -> 0
                }
                if (direction != 0) {
                    rotaryAccumulator = 0f
                    onProjectStep(direction)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 70.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Project", style = MaterialTheme.typography.labelSmall)
                Text(
                    state.selectedProject.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(transcriptScroll),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val bodyText = state.responseText.takeIf(String::isNotBlank) ?: state.transcript
                if (bodyText.isNotBlank()) {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = state.statusLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.phase in setOf(
                        AssistantPhaseUi.CONNECTING,
                        AssistantPhaseUi.SUBMITTING,
                        AssistantPhaseUi.WAITING,
                        AssistantPhaseUi.STREAMING,
                    )
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                state.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.phase) {
                AssistantPhaseUi.LISTENING -> {
                    FilledIconButton(onClick = onRedo, enabled = state.canRedo) {
                        Icon(Icons.Filled.Replay, contentDescription = "Redo")
                    }
                    FilledIconButton(onClick = onSend, enabled = state.canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
                AssistantPhaseUi.ERROR -> FilledIconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                }
                AssistantPhaseUi.NEEDS_ATTENTION -> {
                    FilledIconButton(onClick = onDecline) {
                        Icon(Icons.Filled.Replay, contentDescription = "Decline")
                    }
                    FilledIconButton(onClick = onApprove) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Approve")
                    }
                }
                AssistantPhaseUi.HANDED_OFF, AssistantPhaseUi.COMPLETE -> FilledIconButton(onClick = onDone) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Done")
                }
                else -> Unit
            }
        }
    }
}

private fun AssistantUiState.statusLabel(): String =
    when (phase) {
        AssistantPhaseUi.CONNECTING -> "Connecting"
        AssistantPhaseUi.LISTENING -> "Listening"
        AssistantPhaseUi.SUBMITTING -> "Sending"
        AssistantPhaseUi.WAITING -> "Codex is thinking"
        AssistantPhaseUi.STREAMING -> "Codex is responding"
        AssistantPhaseUi.NEEDS_ATTENTION -> "Codex needs attention"
        AssistantPhaseUi.HANDED_OFF -> "Task is running in the background"
        AssistantPhaseUi.COMPLETE -> "Complete"
        AssistantPhaseUi.ERROR -> "Couldn’t reach Codex"
    }

private const val ROTARY_STEP_THRESHOLD_PX = 42f
