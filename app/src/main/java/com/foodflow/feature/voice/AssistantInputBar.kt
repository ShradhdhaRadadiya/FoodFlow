package com.foodflow.feature.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * ChatGPT-style unified input bar for FoodFlow AI Assistant:
 * - When input field is empty: shows text input + Microphone icon (Send icon is hidden).
 * - When user types non-blank text: shows text input + Send icon (Microphone icon is hidden).
 * - While assistant is actively processing a request with empty input: shows Stop button so the
 *   user can interrupt cleanly at any time.
 * - Never shows Microphone and Send simultaneously.
 */
@Composable
fun AssistantInputBar(
    text: String,
    isProcessing: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasText = text.isNotBlank()
    val actionMode = when {
        hasText -> "SEND"
        isProcessing -> "STOP"
        else -> "MIC"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_input_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    text = "Message FoodFlow AI...",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (hasText) onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .testTag("voice_text_input")
        )

        AnimatedContent(
            targetState = actionMode,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(initialScale = 0.82f, animationSpec = tween(150)))
                    .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.82f, animationSpec = tween(120)))
            },
            label = "chatgpt_input_action_swap"
        ) { mode ->
            when (mode) {
                "SEND" -> {
                    Surface(
                        onClick = onSubmit,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Send message"
                            }
                            .testTag("voice_send_text_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                "STOP" -> {
                    Surface(
                        onClick = onStopClick,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Stop assistant"
                            }
                            .testTag("voice_stop_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                else -> {
                    Surface(
                        onClick = onMicClick,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Start voice input"
                            }
                            .testTag("voice_mic_input_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
