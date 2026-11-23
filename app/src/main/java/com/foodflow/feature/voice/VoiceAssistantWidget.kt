package com.foodflow.feature.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceSheetDetent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Single reusable draggable floating Voice Assistant trigger rendered ONCE in FoodFlowRoot
 * whenever the persistent bottom sheet is hidden.
 *
 * Features:
 * - Smooth drag-and-drop across the entire safe viewport (clamped inside status/nav bars & edges).
 * - Automatic spring edge-snap to the nearest horizontal screen edge on release.
 * - Clean drag vs tap separation: dragging moves the button without accidentally opening the sheet.
 * - Position persists across navigation and recomposition via normalized edge + vertical fraction.
 */
@Composable
fun VoiceAssistantWidget(
    uiState: VoiceAssistantUiState,
    visible: Boolean,
    bottomOffset: Dp,
    snappedRight: Boolean,
    verticalFraction: Float?,
    onPositionChanged: (snappedRight: Boolean, verticalFraction: Float) -> Unit,
    onOpenAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showTrigger = visible && (!uiState.isSheetOpen || uiState.sheetDetent == VoiceSheetDetent.HIDDEN)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()

        val buttonOuterSizeDp = 60.dp
        val edgePaddingPx = with(density) { 12.dp.toPx() }
        val topMarginPx = with(density) { 12.dp.toPx() }
        val buttonSizePx = with(density) { buttonOuterSizeDp.toPx() }
        val bottomClearancePx = with(density) { bottomOffset.toPx() }

        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        val bounds = remember(maxWidthPx, maxHeightPx, bottomClearancePx) {
            val minX = edgePaddingPx
            val maxX = (maxWidthPx - buttonSizePx - edgePaddingPx).coerceAtLeast(minX)
            val minY = topMarginPx
            val maxY = (maxHeightPx - buttonSizePx - edgePaddingPx).coerceAtLeast(minY)
            FloatingButtonBounds(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
        }

        val defaultY = remember(bounds, bottomClearancePx) {
            (bounds.maxY - bottomClearancePx).coerceIn(bounds.minY, bounds.maxY)
        }

        val initialPos = remember(bounds, snappedRight, verticalFraction, defaultY) {
            if (verticalFraction != null) {
                FloatingAssistantPositionMath.fromNormalized(snappedRight, verticalFraction, bounds)
            } else {
                FloatingButtonPosition(
                    x = if (snappedRight) bounds.maxX else bounds.minX,
                    y = defaultY
                )
            }
        }

        val offsetX = remember { Animatable(initialPos.x) }
        val offsetY = remember { Animatable(initialPos.y) }
        var isDragging by remember { mutableStateOf(false) }

        // Keep button cleanly clamped and positioned when screen bounds or bottomOffset change
        LaunchedEffect(bounds, snappedRight, verticalFraction, defaultY) {
            if (!isDragging) {
                val target = if (verticalFraction != null) {
                    FloatingAssistantPositionMath.fromNormalized(snappedRight, verticalFraction, bounds)
                } else {
                    FloatingButtonPosition(
                        x = if (snappedRight) bounds.maxX else bounds.minX,
                        y = defaultY
                    )
                }
                offsetX.snapTo(target.x)
                offsetY.snapTo(target.y)
            }
        }

        AnimatedVisibility(
            visible = showTrigger,
            enter = fadeIn(tween(200)) + scaleIn(tween(220)),
            exit = fadeOut(tween(150)) + scaleOut(tween(150)),
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = offsetX.value.roundToInt(),
                        y = offsetY.value.roundToInt()
                    )
                }
                .pointerInput(bounds) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragCancel = {
                            isDragging = false
                            coroutineScope.launch {
                                val snapped = FloatingAssistantPositionMath.snapToNearestHorizontalEdge(
                                    x = offsetX.value,
                                    y = offsetY.value,
                                    bounds = bounds
                                )
                                onPositionChanged(
                                    FloatingAssistantPositionMath.isSnappedRight(snapped.x, bounds),
                                    FloatingAssistantPositionMath.verticalFraction(snapped.y, bounds)
                                )
                                offsetX.animateTo(
                                    targetValue = snapped.x,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            coroutineScope.launch {
                                val snapped = FloatingAssistantPositionMath.snapToNearestHorizontalEdge(
                                    x = offsetX.value,
                                    y = offsetY.value,
                                    bounds = bounds
                                )
                                onPositionChanged(
                                    FloatingAssistantPositionMath.isSnappedRight(snapped.x, bounds),
                                    FloatingAssistantPositionMath.verticalFraction(snapped.y, bounds)
                                )
                                launch {
                                    offsetX.animateTo(
                                        targetValue = snapped.x,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                                launch {
                                    offsetY.animateTo(
                                        targetValue = snapped.y,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val clamped = FloatingAssistantPositionMath.clamp(
                                x = offsetX.value + dragAmount.x,
                                y = offsetY.value + dragAmount.y,
                                bounds = bounds
                            )
                            coroutineScope.launch {
                                offsetX.snapTo(clamped.x)
                                offsetY.snapTo(clamped.y)
                            }
                        }
                    )
                }
                .testTag("voice_assistant_draggable_container")
        ) {
            val isLive = uiState.isListening || uiState.isSpeaking
            val pulseScale = if (isLive) {
                val infiniteTransition = rememberInfiniteTransition(label = "voice_fab_pulse")
                val value by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.24f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "voice_fab_pulse_scale"
                )
                value
            } else {
                1.0f
            }

            val primaryColor = MaterialTheme.colorScheme.primary
            val tertiaryColor = MaterialTheme.colorScheme.tertiary
            val accentBrush = Brush.linearGradient(
                colors = when {
                    uiState.isSpeaking -> listOf(tertiaryColor, primaryColor)
                    uiState.isListening -> listOf(primaryColor, MaterialTheme.colorScheme.secondary)
                    else -> listOf(primaryColor, tertiaryColor)
                }
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(buttonOuterSizeDp)
            ) {
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(primaryColor.copy(alpha = 0.28f))
                    )
                }

                Surface(
                    shape = CircleShape,
                    shadowElevation = if (isDragging) 12.dp else 6.dp,
                    color = Color.Transparent,
                    border = BorderStroke(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier
                        .size(52.dp)
                        .scale(if (isDragging) 1.08f else 1.0f)
                        .clip(CircleShape)
                        .background(accentBrush)
                        .semantics {
                            contentDescription = "Open FoodFlow AI Voice Assistant (${uiState.statusText})"
                        }
                        .clickable(
                            enabled = !isDragging,
                            onClick = onOpenAssistant
                        )
                        .testTag("voice_assistant_fab")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when (uiState.phase) {
                            VoiceAssistantPhase.LISTENING -> Icons.Filled.Mic
                            VoiceAssistantPhase.SPEAKING -> Icons.Filled.VolumeUp
                            else -> Icons.Filled.GraphicEq
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
