package com.foodflow.feature.orderconfirmation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.foodFlowBottomBarInsets
import com.foodflow.core.designsystem.components.foodFlowStatusBarInsets
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily

/** Shows confirmation details for the submitted order and its next actions. */
@Composable
fun OrderConfirmationScreen(
    orderId: String,
    onTrackOrder: (String) -> Unit,
    onBackHome: () -> Unit,
    viewModel: OrderConfirmationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scale = remember { Animatable(0.5f) }
    val extended = FoodFlowThemeTokens.extendedColors

    LaunchedEffect(orderId) {
        viewModel.onIntent(OrderConfirmationIntent.Load(orderId))
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .foodFlowStatusBarInsets()
                .foodFlowBottomBarInsets()
                .verticalScroll(rememberScrollState())
                .padding(FoodFlowSpacing.lg)
                .testTag("order_confirmation_screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = extended.vegSurface,
                modifier = Modifier
                    .size(104.dp)
                    .scale(scale.value)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Order confirmed",
                        tint = extended.vegGreen,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Order Confirmed!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your order has been sent to the kitchen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            state.order?.let { order ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConfirmationRow("Restaurant", order.restaurantName)
                        ConfirmationRow(
                            "Items",
                            "${order.items.sumOf { it.quantity }} item${if (order.items.sumOf { it.quantity } == 1) "" else "s"}"
                        )
                        ConfirmationRow("Estimated Arrival", "${order.estimatedDeliveryMinutes} mins")
                        ConfirmationRow("Payment", "${order.paymentMethod.displayName} (${order.paymentStatus.label})")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ConfirmationRow("Total Paid", order.totalPayable.toInr(), highlight = true)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { onTrackOrder(orderId) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirmation_track_order_button")
            ) {
                Text("Track Order Live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onBackHome,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("confirmation_back_home_button")
            ) {
                Text("Back to Home")
            }
        }
    }
}

/** Displays one label and value in the order confirmation summary. */
@Composable
private fun ConfirmationRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = if (highlight) {
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                )
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
