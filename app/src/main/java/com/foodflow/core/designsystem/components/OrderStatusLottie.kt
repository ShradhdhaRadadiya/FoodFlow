package com.foodflow.core.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus

/**
 * Renders a smooth crossfading + spring-scaled Lottie vector animation keyed to [OrderStatus].
 */
@Composable
fun OrderStatusLottieView(
    status: OrderStatus,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp
) {
    val extended = FoodFlowThemeTokens.extendedColors
    val stageColor = when (status) {
        OrderStatus.DELIVERED -> extended.vegSurface
        OrderStatus.CANCELLED -> extended.nonVegSurface
        OrderStatus.PARTNER_ASSIGNED, OrderStatus.PICKED_UP -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
    }

    Surface(
        shape = CircleShape,
        color = stageColor,
        modifier = modifier
            .size(size)
            .testTag("order_status_lottie_${status.name.lowercase()}")
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(tween(280)) + scaleIn(
                    initialScale = 0.78f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                )) togetherWith (fadeOut(tween(180)) + scaleOut(targetScale = 0.85f))
            },
            label = "order_status_lottie_transition"
        ) { targetStatus ->
            val spec = resolveOrderStatusAnimation(targetStatus)
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(spec.rawRes))
            val progress by animateLottieCompositionAsState(
                composition = composition,
                iterations = spec.iterations,
                speed = spec.speed,
                restartOnPlay = true
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .semantics { contentDescription = spec.contentDescription },
                contentAlignment = Alignment.Center
            ) {
                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Hero order status transition card combining [OrderStatusLottieView], animated status copy,
 * ETA badge, delay alert, and live progression/cancellation controls.
 */
@Composable
fun OrderStatusFeedbackCard(
    order: Order,
    onCancelOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = resolveOrderStatusAnimation(order.orderStatus)
    val extended = FoodFlowThemeTokens.extendedColors
    val statusAccent = when (order.orderStatus) {
        OrderStatus.DELIVERED -> extended.vegGreen
        OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("order_status_feedback_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrderStatusLottieView(
                    status = order.orderStatus,
                    size = 84.dp
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedContent(
                        targetState = order.orderStatus,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically { it / 3 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically { -it / 3 })
                        },
                        label = "order_status_title_transition"
                    ) { targetStatus ->
                        Text(
                            text = targetStatus.label,
                            style = MaterialTheme.typography.headlineSmall,
                            color = when (targetStatus) {
                                OrderStatus.DELIVERED -> extended.vegGreen
                                OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.testTag("tracking_order_status")
                        )
                    }
                    Text(
                        text = spec.statusHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Payment: ${order.paymentStatus.label} (${order.paymentMethod.displayName})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (order.orderStatus.isActive) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${order.estimatedDeliveryMinutes}m",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("ETA", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (order.isDelayed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Delayed: ${order.delayReason ?: "Running behind schedule"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (order.orderStatus.isActive && order.orderStatus.canTransitionTo(OrderStatus.CANCELLED)) {
                OutlinedButton(
                    onClick = onCancelOrder,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tracking_cancel_button")
                ) {
                    Text("Cancel Order")
                }
            }
        }
    }
}
