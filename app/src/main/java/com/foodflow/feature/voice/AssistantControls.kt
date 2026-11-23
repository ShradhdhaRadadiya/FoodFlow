package com.foodflow.feature.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodflow.domain.model.VoiceAssistantPhase

/**
 * Focused ChatGPT-style Voice Listening Mode panel that replaces normal text-entry controls
 * while actively listening or speaking.
 *
 * Strictly exposes ONLY Speak and Stop controls (zero Pause, Resume, Mute, or Volume controls)
 * paired with the real-amplitude [AudioVisualizer].
 */
@Composable
fun VoiceListeningPanel(
    phase: VoiceAssistantPhase,
    statusText: String,
    isListening: Boolean,
    canStop: Boolean,
    inputAmplitude: Float,
    outputAmplitude: Float,
    onSpeakClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_listening_mode_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = statusText.ifBlank { if (isListening) "Listening..." else "Speaking..." },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("voice_listening_status_text")
                )
            }

            AudioVisualizer(
                phase = phase,
                inputAmplitude = inputAmplitude,
                outputAmplitude = outputAmplitude
            )

            AssistantControls(
                isListening = isListening,
                canStop = canStop,
                onSpeakClick = onSpeakClick,
                onStopClick = onStopClick
            )
        }
    }
}

/**
 * Fixed-slot dual voice control bar containing ONLY Speak and Stop.
 * Both buttons occupy permanent, identical-dimension 50/50 slots so icons and layout
 * never shift horizontally or vertically across Idle, Listening, Processing, or Speaking states.
 */
@Composable
fun AssistantControls(
    isListening: Boolean,
    canStop: Boolean,
    onSpeakClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speakContainerColor by animateColorAsState(
        targetValue = if (isListening) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(180),
        label = "speak_btn_bg"
    )
    val speakContentColor by animateColorAsState(
        targetValue = if (isListening) {
            MaterialTheme.colorScheme.onSecondary
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(180),
        label = "speak_btn_fg"
    )

    val stopContainerColor by animateColorAsState(
        targetValue = if (canStop) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(180),
        label = "stop_btn_bg"
    )
    val stopContentColor by animateColorAsState(
        targetValue = if (canStop) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(180),
        label = "stop_btn_fg"
    )
    val stopBorderColor by animateColorAsState(
        targetValue = if (canStop) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(180),
        label = "stop_btn_border"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("voice_controls_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed Left Slot: Speak
        Surface(
            onClick = onSpeakClick,
            shape = RoundedCornerShape(14.dp),
            color = speakContainerColor,
            contentColor = speakContentColor,
            shadowElevation = if (isListening) 3.dp else 1.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics {
                    role = Role.Button
                    contentDescription = if (isListening) "Listening to your voice" else "Speak to FoodFlow AI"
                }
                .testTag("voice_speak_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isListening) "Listening" else "Speak",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Fixed Right Slot: Stop
        Surface(
            onClick = onStopClick,
            enabled = canStop,
            shape = RoundedCornerShape(14.dp),
            color = stopContainerColor,
            contentColor = stopContentColor,
            border = BorderStroke(1.dp, stopBorderColor),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics {
                    role = Role.Button
                    contentDescription = "Stop listening or speaking"
                }
                .testTag("voice_stop_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Stop",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
