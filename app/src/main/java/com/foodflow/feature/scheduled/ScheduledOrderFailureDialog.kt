package com.foodflow.feature.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foodflow.domain.model.ScheduledFailureAction
import com.foodflow.domain.model.ScheduledOrderFailurePopup

/**
 * Reusable in-app failure popup displayed whenever a scheduled order cannot be placed
 * at its requested order placement time (restaurant closed, restaurant paused, items unavailable,
 * price changed beyond authorized consent, or payment failure).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduledOrderFailureDialog(
    popup: ScheduledOrderFailurePopup,
    onPrimaryAction: () -> Unit,
    onCancelOrder: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("scheduled_failure_popup_dialog"),
        icon = {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = "Scheduled order placement failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = popup.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("scheduled_failure_popup_title")
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = popup.restaurantName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Schedule #${popup.scheduleId} · ${popup.failureCategory.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Text(
                    text = popup.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("scheduled_failure_popup_message")
                )

                if (popup.unavailableItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Unavailable items:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            popup.unavailableItems.forEach { itemName ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = itemName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onPrimaryAction,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("scheduled_failure_primary_action")
            ) {
                val actionIcon = if (popup.primaryAction == ScheduledFailureAction.REVIEW_ORDER) {
                    Icons.Filled.RestaurantMenu
                } else {
                    Icons.Filled.EditCalendar
                }
                Icon(actionIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(popup.primaryAction.label)
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(
                    onClick = onCancelOrder,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("scheduled_failure_cancel_order_action")
                ) {
                    Text(popup.secondaryAction.label, color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("scheduled_failure_dismiss_action")
                ) {
                    Text("Later")
                }
            }
        }
    )
}
