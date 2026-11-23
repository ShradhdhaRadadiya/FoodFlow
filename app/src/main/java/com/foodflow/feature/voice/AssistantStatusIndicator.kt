package com.foodflow.feature.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodflow.domain.model.VoiceAssistantPhase

/**
 * Compact status indicator showing real user-friendly assistant phases and subtle
 * animated activity feedback during connecting, listening, thinking, or speaking.
 */
@Composable
fun AssistantStatusIndicator(
    phase: VoiceAssistantPhase,
    statusText: String,
    isProcessing: Boolean,
    isFallbackMode: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (phase) {
            VoiceAssistantPhase.LISTENING -> MaterialTheme.colorScheme.primary
            VoiceAssistantPhase.SPEAKING -> MaterialTheme.colorScheme.tertiary
            VoiceAssistantPhase.CONNECTING,
            VoiceAssistantPhase.INITIALIZING,
            VoiceAssistantPhase.PROCESSING,
            VoiceAssistantPhase.THINKING,
            VoiceAssistantPhase.RESPONDING,
            VoiceAssistantPhase.RECONNECTING -> MaterialTheme.colorScheme.secondary
            VoiceAssistantPhase.ERROR -> MaterialTheme.colorScheme.error
            else -> if (isFallbackMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(220),
        label = "status_dot_color"
    )

    val containerColor by animateColorAsState(
        targetValue = when (phase) {
            VoiceAssistantPhase.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            VoiceAssistantPhase.LISTENING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
            VoiceAssistantPhase.SPEAKING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
        animationSpec = tween(220),
        label = "status_pill_container"
    )

    val contentColor = when (phase) {
        VoiceAssistantPhase.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        VoiceAssistantPhase.LISTENING -> MaterialTheme.colorScheme.onPrimaryContainer
        VoiceAssistantPhase.SPEAKING -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val dot1Alpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, delayMillis = 160, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, delayMillis = 320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        modifier = modifier.testTag("voice_status_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isProcessing) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .alpha(dot1Alpha)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .alpha(dot2Alpha)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .alpha(dot3Alpha)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            } else {
                val activePulse = if (phase == VoiceAssistantPhase.LISTENING || phase == VoiceAssistantPhase.SPEAKING) {
                    dot1Alpha
                } else {
                    1f
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(activePulse)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("voice_status_text")
            )
        }
    }
}
