package com.foodflow.feature.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduledOrderHistoryCard(
    schedule: ScheduledOrder,
    imageKey: String,
    placementSentence: String,
    recurrenceSummary: String,
    isExecuting: Boolean,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    onViewDetails: () -> Unit,
    onPlaceNowDemo: () -> Unit,
    onTrackOrder: (String) -> Unit
) {
    val extended = FoodFlowThemeTokens.extendedColors
    val statusColor = when (schedule.status) {
        ScheduledOrderStatus.UPCOMING -> MaterialTheme.colorScheme.primary
        ScheduledOrderStatus.PLACING_ORDER -> MaterialTheme.colorScheme.secondary
        ScheduledOrderStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        ScheduledOrderStatus.COMPLETED -> extended.vegGreen
        ScheduledOrderStatus.FAILED -> MaterialTheme.colorScheme.error
        ScheduledOrderStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val paymentTimingNote = when (schedule.paymentMethod) {
        PaymentMethod.COD -> "Cash on Delivery after placement"
        else -> "${schedule.paymentMethod.displayName} • Charged at placement time"
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetails)
            .testTag("orders_scheduled_card_${schedule.id}")
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FoodImage(
                    imageKey = imageKey,
                    contentDescription = schedule.restaurantName,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = schedule.restaurantName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#${schedule.id} • $recurrenceSummary",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = schedule.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("orders_scheduled_status_${schedule.id}")
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when (schedule.status) {
                            ScheduledOrderStatus.PLACING_ORDER -> "Placing your order with the restaurant now..."
                            ScheduledOrderStatus.COMPLETED -> "Order placed with ${schedule.restaurantName}."
                            ScheduledOrderStatus.CANCELLED -> "Scheduled order was cancelled before placement."
                            else -> placementSentence
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (schedule.status == ScheduledOrderStatus.FAILED && !schedule.lastFailureReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = schedule.lastFailureCategory.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = schedule.lastFailureReason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = schedule.items.joinToString(", ") { "${it.quantity} × ${it.foodName}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = schedule.authorizedTotalPayable.toInr(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = paymentTimingNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (schedule.status == ScheduledOrderStatus.UPCOMING) {
                    Button(
                        onClick = onPlaceNowDemo,
                        enabled = !isExecuting,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("orders_place_now_schedule_${schedule.id}")
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("Place Now")
                    }
                }

                if (schedule.status.canReschedule) {
                    OutlinedButton(
                        onClick = onReschedule,
                        enabled = !isExecuting,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("orders_reschedule_schedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.EditCalendar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reschedule")
                    }
                }

                if (schedule.status.canCancel) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !isExecuting,
                        modifier = Modifier.testTag("orders_cancel_schedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }

                // Only allow tracking once an actual order has been placed with the restaurant.
                schedule.lastGeneratedOrderId?.let { generatedOrderId ->
                    OutlinedButton(
                        onClick = { onTrackOrder(generatedOrderId) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("orders_track_generated_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Track Order")
                    }
                }

                TextButton(
                    onClick = onViewDetails,
                    modifier = Modifier.testTag("orders_details_schedule_${schedule.id}")
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
            }
        }
    }
}

@Composable
fun ScheduledOrderHistoryDetailsDialog(
    schedule: ScheduledOrder,
    placementSentence: String,
    recurrenceSummary: String,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    onTrackOrder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(schedule.restaurantName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "#${schedule.id} • ${schedule.status.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = placementSentence,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = recurrenceSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text("Delivery Address", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = schedule.deliveryAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Scheduled Items", style = MaterialTheme.typography.labelLarge)
                schedule.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${item.quantity} × ${item.foodName}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = (item.unitPrice * item.quantity).toInr(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Estimated Total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = schedule.authorizedTotalPayable.toInr(),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Payment: ${schedule.paymentMethod.displayName} • ${schedule.priceConsentPolicy.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!schedule.lastFailureReason.isNullOrBlank()) {
                    Text(
                        text = "Last failure (${schedule.lastFailureCategory.label}): ${schedule.lastFailureReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when {
                schedule.lastGeneratedOrderId != null -> Button(
                    onClick = { onTrackOrder(schedule.lastGeneratedOrderId) }
                ) { Text("Track Order") }
                schedule.status.canReschedule -> Button(onClick = onReschedule) { Text("Reschedule") }
                else -> Button(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (schedule.status.canCancel) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel Order", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersRescheduleDialogs(
    schedule: ScheduledOrder,
    draftUtcMs: Long,
    showDatePicker: Boolean,
    showTimePicker: Boolean,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onConfirmDate: (Long) -> Unit,
    onDismissDate: () -> Unit,
    onConfirmTime: (Int, Int) -> Unit,
    onDismissTime: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateText = remember(draftUtcMs) {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(Date(draftUtcMs))
    }
    val timeText = remember(draftUtcMs) {
        SimpleDateFormat("hh:mm a", Locale.US).format(Date(draftUtcMs))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reschedule Order Placement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select a new future date and time to place your order with ${schedule.restaurantName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onPickDate,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(dateText)
                }
                OutlinedButton(
                    onClick = onPickTime,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(timeText)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                modifier = Modifier.testTag("orders_confirm_reschedule_button")
            ) {
                Text("Save New Time")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        val todayStartUtcMs = remember {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = draftUtcMs,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayStartUtcMs
            }
        )
        DatePickerDialog(
            onDismissRequest = onDismissDate,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let(onConfirmDate) ?: onDismissDate()
                    }
                ) { Text("Select") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDate) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val cal = remember(draftUtcMs) {
            Calendar.getInstance().apply { timeInMillis = draftUtcMs }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = onDismissTime,
            title = { Text("Select Order Placement Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                Button(onClick = { onConfirmTime(timePickerState.hour, timePickerState.minute) }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissTime) { Text("Cancel") }
            }
        )
    }
}
