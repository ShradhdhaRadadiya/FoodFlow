package com.foodflow.core.designsystem.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.OutdoorGrill
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderTimelineEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated visual food preparation & delivery journey card driven strictly by real [Order.orderStatus]
 * and [Order.riderProgress]. Battery-friendly Compose Canvas + Material vector animations that
 * respect system reduced-motion settings.
 */
@Composable
fun OrderDeliveryJourneyCard(
    order: Order,
    timelineEvents: List<OrderTimelineEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }

    val stage = resolveOrderJourneyStage(order.orderStatus, order.riderProgress)
    val milestoneTimings = remember(order, timelineEvents) {
        resolveJourneyMilestoneTimings(order, timelineEvents)
    }
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.US) }
    val extended = FoodFlowThemeTokens.extendedColors
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    val accentColor = when (stage) {
        OrderJourneyStage.DELIVERED -> extended.vegGreen
        OrderJourneyStage.CANCELLED -> errorColor
        OrderJourneyStage.PICKED_UP, OrderJourneyStage.ON_THE_WAY -> secondary
        else -> primary
    }

    val motionPhase = if (animationsEnabled && stage != OrderJourneyStage.CANCELLED && stage != OrderJourneyStage.DELIVERED) {
        val infinite = rememberInfiniteTransition(label = "order_journey_motion")
        val value by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "order_journey_phase"
        )
        value
    } else {
        0f
    }

    val animatedRiderFraction by animateFloatAsState(
        targetValue = when (stage) {
            OrderJourneyStage.CONFIRMED -> 0.08f
            OrderJourneyStage.PREPARING -> 0.24f
            OrderJourneyStage.READY_FOR_PICKUP -> 0.42f
            OrderJourneyStage.PARTNER_ASSIGNED -> 0.50f
            OrderJourneyStage.PICKED_UP -> (0.58f + order.riderProgress.coerceIn(0f, 1f) * 0.34f).coerceIn(0.58f, 0.92f)
            OrderJourneyStage.ON_THE_WAY -> (0.62f + order.riderProgress.coerceIn(0f, 1f) * 0.32f).coerceIn(0.62f, 0.94f)
            OrderJourneyStage.DELIVERED -> 1f
            OrderJourneyStage.CANCELLED -> 0f
        },
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "order_journey_progress"
    )

    val headerTimingText = remember(stage, milestoneTimings, order.estimatedDeliveryMinutes) {
        val deliveredTiming = milestoneTimings.lastOrNull()
        when {
            stage == OrderJourneyStage.DELIVERED && deliveredTiming?.timestampMs != null ->
                "Delivered ${timeFormatter.format(Date(deliveredTiming.timestampMs))}"
            stage != OrderJourneyStage.CANCELLED && order.estimatedDeliveryMinutes > 0 ->
                "ETA ${order.estimatedDeliveryMinutes} min"
            else -> null
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, accentColor.copy(alpha = 0.28f)),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${stage.title}: ${stage.subtitle}" }
            .testTag("order_delivery_journey_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = stage.badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                if (stage != OrderJourneyStage.CANCELLED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (headerTimingText != null) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                border = BorderStroke(0.5.dp, accentColor.copy(alpha = 0.25f)),
                                modifier = Modifier.testTag("order_journey_header_timing")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Schedule,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = headerTimingText,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.5.sp
                                        ),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${(animatedRiderFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Stage-Specific Animated Visual Scene
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = accentColor.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(106.dp)
                    .testTag("order_journey_stage_${stage.name.lowercase()}")
            ) {
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInHorizontally { it / 5 }) togetherWith
                            (fadeOut(tween(180)) + slideOutHorizontally { -it / 5 })
                    },
                    label = "order_journey_stage_scene"
                ) { currentStage ->
                    JourneyStageVisualScene(
                        stage = currentStage,
                        motionPhase = motionPhase,
                        riderFraction = animatedRiderFraction,
                        accentColor = accentColor,
                        tertiaryColor = tertiary,
                        trackColor = outlineColor
                    )
                }
            }

            // Stage Description & 5-Node Journey Strip
            AnimatedContent(
                targetState = stage,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "order_journey_copy"
            ) { currentStage ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = currentStage.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("order_journey_stage_title")
                    )
                    Text(
                        text = currentStage.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (stage != OrderJourneyStage.CANCELLED) {
                JourneyMilestoneStrip(
                    activeStepIndex = stage.stepIndex,
                    milestones = milestoneTimings,
                    timeFormatter = timeFormatter,
                    accentColor = accentColor
                )
            }
        }
    }
}

/** Animates the illustration for the resolved order stage using bounded motion-phase values. */
@Composable
private fun JourneyStageVisualScene(
    stage: OrderJourneyStage,
    motionPhase: Float,
    riderFraction: Float,
    accentColor: Color,
    tertiaryColor: Color,
    trackColor: Color
) {
    val bobOffset = (sin(motionPhase * 2.0 * PI) * 5.0).toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val laneY = size.height * 0.76f
            val startX = 28f
            val endX = size.width - 28f
            val activeEndX = startX + (endX - startX) * riderFraction.coerceIn(0f, 1f)

            when (stage) {
                OrderJourneyStage.PREPARING -> {
                    // Gentle rising steam ribbons above the kitchen stove
                    for (idx in 0 until 3) {
                        val cx = size.width * (0.42f + idx * 0.08f)
                        val waveShift = ((motionPhase + idx * 0.33f) % 1f)
                        val steamAlpha = (1f - waveShift).coerceIn(0.15f, 0.75f)
                        val path = Path().apply {
                            moveTo(cx, size.height * 0.56f)
                            quadraticTo(
                                cx + sin(waveShift * 2 * PI).toFloat() * 14f,
                                size.height * (0.36f - waveShift * 0.14f),
                                cx - sin(waveShift * 2 * PI).toFloat() * 10f,
                                size.height * (0.14f - waveShift * 0.08f)
                            )
                        }
                        drawPath(
                            path = path,
                            color = accentColor.copy(alpha = steamAlpha),
                            style = Stroke(width = 4f, cap = StrokeCap.Round)
                        )
                    }
                }

                OrderJourneyStage.PICKED_UP, OrderJourneyStage.ON_THE_WAY -> {
                    // Animated road dashes moving backward under the riding bike
                    drawLine(
                        color = trackColor.copy(alpha = 0.6f),
                        start = Offset(startX, laneY),
                        end = Offset(endX, laneY),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(accentColor, tertiaryColor)),
                        start = Offset(startX, laneY),
                        end = Offset(activeEndX, laneY),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = Offset(startX, laneY),
                        end = Offset(activeEndX, laneY),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(
                            intervals = floatArrayOf(12f, 12f),
                            phase = motionPhase * 48f
                        )
                    )
                }

                OrderJourneyStage.DELIVERED -> {
                    drawLine(
                        color = accentColor,
                        start = Offset(startX, laneY),
                        end = Offset(endX, laneY),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                }

                else -> {
                    // Clean journey track for Confirmed / Ready / Partner Assigned
                    drawLine(
                        color = trackColor.copy(alpha = 0.55f),
                        start = Offset(startX, laneY),
                        end = Offset(endX, laneY),
                        strokeWidth = 5f,
                        cap = StrokeCap.Round
                    )
                    if (stage != OrderJourneyStage.CANCELLED) {
                        drawLine(
                            color = accentColor,
                            start = Offset(startX, laneY),
                            end = Offset(activeEndX, laneY),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        // Foreground stage icons strictly matching real OrderStatus
        when (stage) {
            OrderJourneyStage.CONFIRMED -> {
                StageCenterIcon(
                    icon = Icons.Filled.Restaurant,
                    tint = accentColor,
                    translationY = bobOffset * 0.4f,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            OrderJourneyStage.PREPARING -> {
                StageCenterIcon(
                    icon = Icons.Filled.OutdoorGrill,
                    tint = accentColor,
                    translationY = bobOffset * 0.5f,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            OrderJourneyStage.READY_FOR_PICKUP -> {
                StageCenterIcon(
                    icon = Icons.Filled.Inventory2,
                    tint = accentColor,
                    translationY = bobOffset * 0.6f,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            OrderJourneyStage.PARTNER_ASSIGNED -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StageCenterIcon(
                        icon = Icons.Filled.Restaurant,
                        tint = accentColor,
                        translationY = 0f
                    )
                    StageCenterIcon(
                        icon = Icons.Filled.DeliveryDining,
                        tint = tertiaryColor,
                        translationY = bobOffset * 0.6f
                    )
                }
            }
            OrderJourneyStage.PICKED_UP, OrderJourneyStage.ON_THE_WAY -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restaurant,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                    StageCenterIcon(
                        icon = Icons.Filled.TwoWheeler,
                        tint = accentColor,
                        translationY = bobOffset * 0.5f
                    )
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            OrderJourneyStage.DELIVERED -> {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StageCenterIcon(
                        icon = Icons.Filled.CheckCircle,
                        tint = accentColor,
                        translationY = 0f
                    )
                    StageCenterIcon(
                        icon = Icons.Filled.Home,
                        tint = accentColor,
                        translationY = 0f
                    )
                }
            }
            OrderJourneyStage.CANCELLED -> {
                StageCenterIcon(
                    icon = Icons.Filled.Cancel,
                    tint = accentColor,
                    translationY = 0f,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/** Draws the center glyph for a journey scene with the stage's current tint. */
@Composable
private fun StageCenterIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    translationY: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = modifier
            .size(48.dp)
            .graphicsLayer { this.translationY = translationY }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** Renders completed/current/future milestones and their actual or estimated timestamps. */
@Composable
private fun JourneyMilestoneStrip(
    activeStepIndex: Int,
    milestones: List<OrderJourneyMilestoneTiming>,
    timeFormatter: SimpleDateFormat,
    accentColor: Color
) {
    val trackInactive = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val lastIndex = milestones.lastIndex

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("order_journey_milestone_strip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            milestones.forEachIndexed { index, milestone ->
                val isReached = index <= activeStepIndex
                val isCurrentActive = index == activeStepIndex && activeStepIndex < lastIndex
                val isCompleted = index < activeStepIndex || (index == activeStepIndex && activeStepIndex == lastIndex)
                val formattedTime = milestone.timestampMs?.let { ts ->
                    val clock = timeFormatter.format(Date(ts))
                    if (milestone.isEstimated) "~$clock" else clock
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Connected horizontal track + milestone node
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth(0.5f)
                                    .height(2.5.dp)
                                    .background(if (isReached) accentColor else trackInactive)
                            )
                        }
                        if (index < lastIndex) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxWidth(0.5f)
                                    .height(2.5.dp)
                                    .background(if (index < activeStepIndex) accentColor else trackInactive)
                            )
                        }

                        when {
                            isCurrentActive -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = accentColor.copy(alpha = 0.22f),
                                        modifier = Modifier.size(22.dp)
                                    ) {}
                                    Surface(
                                        shape = CircleShape,
                                        color = accentColor,
                                        modifier = Modifier.size(14.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.White,
                                                modifier = Modifier.size(5.dp)
                                            ) {}
                                        }
                                    }
                                }
                            }

                            isCompleted -> {
                                Surface(
                                    shape = CircleShape,
                                    color = accentColor,
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }

                            else -> {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.size(14.dp)
                                ) {}
                            }
                        }
                    }

                    Text(
                        text = milestone.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            letterSpacing = (-0.1).sp
                        ),
                        fontWeight = if (isReached) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isReached) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (formattedTime != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when {
                                isCurrentActive -> accentColor.copy(alpha = 0.16f)
                                isReached -> accentColor.copy(alpha = 0.09f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = BorderStroke(
                                width = 0.5.dp,
                                color = when {
                                    isCurrentActive -> accentColor.copy(alpha = 0.38f)
                                    isReached -> accentColor.copy(alpha = 0.18f)
                                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                }
                            ),
                            modifier = Modifier.testTag("order_journey_step_time_$index")
                        ) {
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 8.5.sp,
                                    letterSpacing = (-0.3).sp
                                ),
                                fontWeight = if (isReached) FontWeight.SemiBold else FontWeight.Medium,
                                color = when {
                                    isCurrentActive -> accentColor
                                    isReached -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                },
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "--:--",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 8.5.sp,
                                letterSpacing = (-0.2).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .testTag("order_journey_step_time_$index")
                        )
                    }
                }
            }
        }
    }
}
