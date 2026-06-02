package com.sidekick.watch.ui

import android.graphics.RuntimeShader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

private const val GLOW_SHADER_SRC = """
    uniform float2 resolution;
    uniform float intensity;
    uniform float time;
    uniform float3 color1;
    uniform float3 color2;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float2 center = float2(0.5, 0.5);
        float radius = 0.5;

        // Distance from center of circle
        float dist = length(uv - center);

        // Clip to circle
        if (dist > radius) return half4(0.0);

        // Distance from bottom edge of circle (0 at edge, 1 at center)
        // Bottom of circle is at (0.5, 1.0) in UV space
        float2 bottomCenter = float2(0.5, 1.0);
        float distFromBottom = length(uv - bottomCenter);

        // Normalize: 0 at bottom edge, 1 far from bottom
        float edgeDist = distFromBottom / (radius * 2.0);

        // Glow falloff — strongest at bottom, fades upward
        float glow = smoothstep(0.45, 0.0, edgeDist) * intensity;

        // Add subtle animated wave for organic feel
        float wave = sin(uv.x * 8.0 + time * 2.0) * 0.03
                   + sin(uv.x * 12.0 - time * 1.5) * 0.02;
        glow += wave * intensity;
        glow = clamp(glow, 0.0, 1.0);

        // Lateral falloff — fade toward left/right edges
        float lateral = 1.0 - smoothstep(0.15, 0.5, abs(uv.x - 0.5));
        glow *= lateral;

        // Blend two colors: color1 at center-bottom, color2 toward sides
        float colorMix = smoothstep(0.0, 0.35, abs(uv.x - 0.5));
        float3 col = mix(color1, color2, colorMix);

        return half4(col * glow, glow);
    }
"""

@Composable
fun VoiceListeningScreen(rmsLevel: Float = 0f, partialText: String = "", isReady: Boolean = true) {
    // Warm-up: yellow/orange, Ready: theme primary/tertiary
    val warmupColor1 = Color(0xFFFFB347) // orange
    val warmupColor2 = Color(0xFFFFD700) // gold

    val color1 by animateColorAsState(
        targetValue = if (isReady) MaterialTheme.colorScheme.primary else warmupColor1,
        animationSpec = tween(600),
        label = "color1",
    )
    val color2 by animateColorAsState(
        targetValue = if (isReady) MaterialTheme.colorScheme.tertiary else warmupColor2,
        animationSpec = tween(600),
        label = "color2",
    )

    val normalizedRms = ((rmsLevel + 2f) / 12f).coerceIn(0f, 1f)
    val animatedRms by animateFloatAsState(
        targetValue = normalizedRms,
        animationSpec = tween(80),
        label = "rms",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "idle")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "idle_pulse",
    )

    val intensity = maxOf(idlePulse, animatedRms)

    val shader = remember { RuntimeShader(GLOW_SHADER_SRC) }
    val shaderBrush = remember { ShaderBrush(shader) }

    // Continuous time for shader animation
    val time by produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis { frameMs ->
                value = frameMs / 1000f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("intensity", intensity)
            shader.setFloatUniform("time", time)
            shader.setFloatUniform(
                "color1",
                color1.red, color1.green, color1.blue,
            )
            shader.setFloatUniform(
                "color2",
                color2.red, color2.green, color2.blue,
            )

            drawRect(brush = shaderBrush)
        }

        // Transcription text
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (partialText.isNotEmpty()) {
                Text(
                    text = partialText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                if (isReady) {
                    Text(
                        text = "Ask Sidekick",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Preview(device = "id:wearos_large_round", showBackground = true)
@Composable
private fun VoiceListeningScreenPreview() {
    MaterialTheme {
        VoiceListeningScreen(rmsLevel = 5f, partialText = "Set a timer for 10 minutes")
    }
}

@Preview(device = "id:wearos_large_round", showBackground = true)
@Composable
private fun VoiceListeningScreenIdlePreview() {
    MaterialTheme {
        VoiceListeningScreen(rmsLevel = 0f, partialText = "")
    }
}
