package com.foodflow.feature.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.foodflow.domain.model.VoiceAssistantPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Real-amplitude voice waveform visualizer.
 *
 * Strict behavior:
 * - Reacts genuinely to real microphone `inputAmplitude` when LISTENING and real playback
 *   `outputAmplitude` when SPEAKING.
 * - Shows ZERO fake waveform movement when idle or when not actively recording/speaking.
 */
@Composable
fun AudioVisualizer(
    phase: VoiceAssistantPhase,
    inputAmplitude: Float,
    outputAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val isListening = phase == VoiceAssistantPhase.LISTENING
    val isSpeaking = phase == VoiceAssistantPhase.SPEAKING
    val isAudioActive = isListening || isSpeaking

    val rawAmplitude = when {
        isListening -> inputAmplitude.coerceIn(0f, 1f)
        isSpeaking -> outputAmplitude.coerceIn(0f, 1f)
        else -> 0f
    }

    val smoothedAmplitude by animateFloatAsState(
        targetValue = rawAmplitude,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "visualizer_real_amplitude"
    )

    val targetHeight by animateDpAsState(
        targetValue = if (isAudioActive) 34.dp else 10.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "visualizer_height"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val idleTint = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(targetHeight)
            .testTag("voice_audio_visualizer")
    ) {
        val barCount = 27
        val spacing = size.width / (barCount * 1.9f)
        val barWidth = ((size.width - spacing * (barCount - 1)) / barCount).coerceIn(3f, 8f)
        val totalWidth = barCount * barWidth + (barCount - 1) * spacing
        val startX = (size.width - totalWidth) / 2f
        val centerY = size.height / 2f
        val centerIndex = (barCount - 1) / 2f

        val gradientBrush = Brush.horizontalGradient(
            colors = when {
                isListening -> listOf(secondary, primary, secondary)
                isSpeaking -> listOf(tertiary, primary, tertiary)
                else -> listOf(idleTint.copy(alpha = 0.55f), idleTint.copy(alpha = 0.55f))
            }
        )

        for (i in 0 until barCount) {
            val normalizedDistance = abs(i - centerIndex) / centerIndex.coerceAtLeast(1f)
            val envelope = (1f - normalizedDistance * 0.72f).coerceIn(0.16f, 1f)

            // Purely driven by real audio amplitude; zero fake motion when quiet/idle
            val harmonic = if (isAudioActive && smoothedAmplitude > 0.01f) {
                (sin((i * 0.65f + smoothedAmplitude * 4.5f) * PI.toFloat() / 3f) + 1f) * 0.5f
            } else {
                0f
            }

            val heightFraction = if (isAudioActive && smoothedAmplitude > 0.01f) {
                (0.16f + smoothedAmplitude * envelope * (0.45f + 0.55f * harmonic)).coerceIn(0.16f, 0.96f)
            } else {
                0.14f
            }

            val barHeight = (size.height * heightFraction).coerceAtLeast(4f)
            val left = startX + i * (barWidth + spacing)

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(left, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
