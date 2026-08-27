package com.codex.wear.ui

import android.graphics.RuntimeShader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.codex.wear.viewmodel.AssistantPhaseUi
import com.codex.wear.viewmodel.AssistantUiState

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
    val bodyText = state.responseText.takeIf(String::isNotBlank) ?: state.transcript
    val showWelcome = state.phase == AssistantPhaseUi.LISTENING && bodyText.isBlank()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(bodyText, transcriptScroll.maxValue) {
        if (bodyText.isNotBlank()) transcriptScroll.scrollTo(transcriptScroll.maxValue)
    }

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
        AssistantAudioGlow(
            rmsLevel = state.rmsLevel,
            isListening = state.phase == AssistantPhaseUi.LISTENING,
        )

        if (state.phase == AssistantPhaseUi.LISTENING) {
            Text(
                text = state.selectedProject.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 23.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        if (showWelcome) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 46.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CodexMark(modifier = Modifier.size(38.dp))
                Text(
                    text = "How can I help?",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 25.dp, top = 68.dp, end = 25.dp, bottom = 82.dp)
                    .verticalScroll(transcriptScroll),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = bodyText.ifBlank { state.statusLabel() },
                    style = if (bodyText.isBlank()) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = Color.White.copy(alpha = if (bodyText.isBlank()) 0.72f else 1f),
                    textAlign = TextAlign.Center,
                )
                if (state.phase in PROGRESS_PHASES) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 10.dp))
                }
                state.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        AssistantActions(
            state = state,
            onRedo = onRedo,
            onSend = onSend,
            onRetry = onRetry,
            onApprove = onApprove,
            onDecline = onDecline,
            onDone = onDone,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

@Composable
private fun AssistantAudioGlow(
    rmsLevel: Float,
    isListening: Boolean,
) {
    val normalizedRms = ((rmsLevel + 2f) / 12f).coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = if (isListening) normalizedRms else 0f,
        animationSpec = tween(90),
        label = "assistant_audio_level",
    )
    val shader = remember { RuntimeShader(ASSISTANT_GLOW_SHADER) }
    val shaderBrush = remember { ShaderBrush(shader) }
    val time by produceState(0f, isListening) {
        if (!isListening) return@produceState
        while (true) {
            withInfiniteAnimationFrameMillis { frameMs -> value = frameMs / 1_000f }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("level", animatedLevel)
        shader.setFloatUniform("time", time)
        drawRect(brush = shaderBrush)
    }
}

@Composable
private fun CodexMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension * 0.39f
        val strokeWidth = size.minDimension * 0.07f
        repeat(6) { index ->
            rotate(index * 60f, pivot = center) {
                val path = Path().apply {
                    moveTo(centerX, centerY - radius)
                    cubicTo(
                        centerX + radius * 0.62f,
                        centerY - radius * 1.04f,
                        centerX + radius * 1.03f,
                        centerY - radius * 0.52f,
                        centerX + radius * 0.80f,
                        centerY + radius * 0.04f,
                    )
                    lineTo(centerX + radius * 0.34f, centerY + radius * 0.50f)
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun AssistantActions(
    state: AssistantUiState,
    onRedo: () -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.phase) {
            AssistantPhaseUi.LISTENING -> if (state.transcript.isNotBlank()) {
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

private val PROGRESS_PHASES = setOf(
    AssistantPhaseUi.CONNECTING,
    AssistantPhaseUi.SUBMITTING,
    AssistantPhaseUi.WAITING,
    AssistantPhaseUi.STREAMING,
)

private const val ROTARY_STEP_THRESHOLD_PX = 42f

private const val ASSISTANT_GLOW_SHADER = """
    uniform float2 resolution;
    uniform float level;
    uniform float time;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float aspect = resolution.x / resolution.y;
        float2 center = float2(0.5, -0.02);
        float2 delta = (uv - center) * float2(aspect, 1.0);
        float angle = atan(delta.y, delta.x);
        float wave = sin(angle * 8.0 + time * 3.2) * (0.006 + level * 0.024)
                   + sin(angle * 13.0 - time * 2.1) * level * 0.012;
        float distanceFromCenter = length(delta) + wave;

        float innerEdge = 0.53 - level * 0.08;
        float glow = smoothstep(innerEdge, 1.015, distanceFromCenter);
        float outerMask = 1.0 - smoothstep(1.015, 1.045, distanceFromCenter);
        float lowerMask = smoothstep(0.30, 0.96, uv.y);
        float brightness = (0.56 + level * 0.44) * glow * outerMask * lowerMask;

        float3 lavender = float3(0.46, 0.51, 1.0);
        float3 pearl = float3(0.93, 0.95, 1.0);
        float3 silver = float3(0.66, 0.70, 0.78);
        float3 color = mix(lavender, pearl, smoothstep(0.10, 0.58, uv.x));
        color = mix(color, silver, smoothstep(0.68, 1.0, uv.x));

        return half4(color * brightness, brightness);
    }
"""
