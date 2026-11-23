package com.foodflow.feature.scheduled

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.ScheduleExecutionStatus
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderExecution
import com.foodflow.domain.model.ScheduledOrderStatus
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Displays scheduled orders and lets the user manage their status and timing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduledOrdersScreen(
    onBack: () -> Unit,
    onTrackOrder: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit = {},
    viewModel: ScheduledOrdersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ScheduledOrdersEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is ScheduledOrdersEffect.NavigateToTrackOrder -> onTrackOrder(effect.orderId)
                is ScheduledOrdersEffect.NavigateToRestaurant -> onOpenRestaurant(effect.restaurantId)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Scheduled Orders",
                subtitle = "${state.upcomingSchedules.size} upcoming • ${state.schedules.size} total",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { viewModel.onIntent(ScheduledOrdersIntent.RunOverdueReconciliation) },
                        enabled = !state.isReconciling,
                        modifier = Modifier.testTag("reconcile_schedules_button")
                    ) {
                        if (state.isReconciling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Check due scheduled orders"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        FoodFlowPullToRefreshBox(
            isRefreshing = state.isReconciling,
            onRefresh = { viewModel.onIntent(ScheduledOrdersIntent.RunOverdueReconciliation) },
            testTag = "scheduled_orders_pull_to_refresh",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("scheduled_orders_list"),
            contentPadding = foodFlowScreenListPadding(
                horizontal = FoodFlowSpacing.md,
                top = FoodFlowSpacing.sm,
                bottomExtra = 28.dp,
                includeNavBarInset = true
            ),
            verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
        ) {
            if (state.isOffline) {
                item(key = "offline_banner") {
                    OfflineBanner(isOffline = true)
                }
            }

            item(key = "schedule_tabs") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ScheduledOrdersTab.entries.forEach { tab ->
                        val count = when (tab) {
                            ScheduledOrdersTab.UPCOMING -> state.upcomingSchedules.size
                            ScheduledOrdersTab.COMPLETED -> state.completedSchedules.size
                            ScheduledOrdersTab.FAILED -> state.failedSchedules.size
                            ScheduledOrdersTab.CANCELLED -> state.cancelledSchedules.size
                        }
                        FilterChip(
                            selected = state.selectedTab == tab,
                            onClick = { viewModel.onIntent(ScheduledOrdersIntent.SelectTab(tab)) },
                            label = { Text("${tab.label} ($count)") },
                            modifier = Modifier.testTag("scheduled_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }

            if (state.visibleSchedules.isEmpty()) {
                item(key = "empty_schedules") {
                    val (title, subtitle) = when (state.selectedTab) {
                        ScheduledOrdersTab.UPCOMING -> "No upcoming schedules" to "Choose Schedule Order during checkout to plan one-time or recurring meals."
                        ScheduledOrdersTab.COMPLETED -> "No completed schedules yet" to "Finished one-time and recurring schedules appear here."
                        ScheduledOrdersTab.FAILED -> "No failed schedules" to "Any schedules that fail availability, price consent, or payment checks appear here."
                        ScheduledOrdersTab.CANCELLED -> "No cancelled schedules" to "Cancelled scheduled orders are kept here for reference."
                    }
                    FoodFlowEmptyState(
                        icon = Icons.Filled.Schedule,
                        title = title,
                        subtitle = subtitle
                    )
                }
            } else {
                items(state.visibleSchedules, key = { it.id }, contentType = { "scheduled_card" }) { schedule ->
                    ScheduledOrderCard(
                        schedule = schedule,
                        placementSentence = viewModel.formatPlacementSentence(schedule),
                        recurrenceSummary = viewModel.formatRuleSummary(schedule.recurrenceRule),
                        isExecuting = state.executingScheduleId == schedule.id,
                        onPause = { viewModel.onIntent(ScheduledOrdersIntent.Pause(schedule.id)) },
                        onResume = { viewModel.onIntent(ScheduledOrdersIntent.Resume(schedule.id)) },
                        onReschedule = { viewModel.onIntent(ScheduledOrdersIntent.OpenReschedule(schedule)) },
                        onCancel = { viewModel.onIntent(ScheduledOrdersIntent.Cancel(schedule.id)) },
                        onViewDetails = { viewModel.onIntent(ScheduledOrdersIntent.OpenDetails(schedule)) },
                        onRunNowDemo = { viewModel.onIntent(ScheduledOrdersIntent.TriggerRunNowForDemo(schedule.id)) },
                        onTrackLatestOrder = { orderId -> onTrackOrder(orderId) }
                    )
                }
            }
        }
        }
    }

    state.detailSchedule?.let { schedule ->
        ScheduledOrderDetailsDialog(
            schedule = schedule,
            recurrenceSummary = viewModel.formatRuleSummary(schedule.recurrenceRule),
            executions = state.detailExecutionHistory,
            onTrackOrder = { orderId ->
                viewModel.onIntent(ScheduledOrdersIntent.DismissDetails)
                onTrackOrder(orderId)
            },
            onDismiss = { viewModel.onIntent(ScheduledOrdersIntent.DismissDetails) }
        )
    }

    state.rescheduleTarget?.let { target ->
        RescheduleDialog(
            schedule = target,
            draftUtcMs = state.rescheduleDraftUtcMs,
            onPickDate = { viewModel.onIntent(ScheduledOrdersIntent.OpenRescheduleDatePicker) },
            onPickTime = { viewModel.onIntent(ScheduledOrdersIntent.OpenRescheduleTimePicker) },
            onSave = { viewModel.onIntent(ScheduledOrdersIntent.SaveReschedule) },
            onDismiss = { viewModel.onIntent(ScheduledOrdersIntent.DismissReschedule) }
        )
    }

    if (state.showRescheduleDatePicker) {
        val todayStartUtcMs = remember {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.rescheduleDraftUtcMs,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayStartUtcMs
            }
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.onIntent(ScheduledOrdersIntent.DismissRescheduleDatePicker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            viewModel.onIntent(ScheduledOrdersIntent.ConfirmRescheduleDate(it))
                        } ?: viewModel.onIntent(ScheduledOrdersIntent.DismissRescheduleDatePicker)
                    }
                ) { Text("Select") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(ScheduledOrdersIntent.DismissRescheduleDatePicker) }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (state.showRescheduleTimePicker) {
        val cal = remember(state.rescheduleDraftUtcMs) {
            Calendar.getInstance().apply { timeInMillis = state.rescheduleDraftUtcMs }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(ScheduledOrdersIntent.DismissRescheduleTimePicker) },
            title = { Text("Select Order Placement Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onIntent(
                            ScheduledOrdersIntent.ConfirmRescheduleTime(
                                hourOfDay = timePickerState.hour,
                                minute = timePickerState.minute
                            )
                        )
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(ScheduledOrdersIntent.DismissRescheduleTimePicker) }) {
                    Text("Cancel")
                }
            }
        )
    }

    state.activeFailurePopup?.let { popup ->
        ScheduledOrderFailureDialog(
            popup = popup,
            onPrimaryAction = { viewModel.onIntent(ScheduledOrdersIntent.HandleFailurePrimaryAction(popup)) },
            onCancelOrder = { viewModel.onIntent(ScheduledOrdersIntent.HandleFailureCancelOrder(popup)) },
            onDismiss = { viewModel.onIntent(ScheduledOrdersIntent.AcknowledgeFailurePopup(popup.scheduleId)) }
        )
    }
}

/** Shows a scheduled order and controls for its current state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledOrderCard(
    schedule: ScheduledOrder,
    placementSentence: String,
    recurrenceSummary: String,
    isExecuting: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    onViewDetails: () -> Unit,
    onRunNowDemo: () -> Unit,
    onTrackLatestOrder: (String) -> Unit
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
    val nextSlotFormatted = remember(schedule.nextExecutionAtUtcMs) {
        SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.US).format(Date(schedule.nextExecutionAtUtcMs))
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scheduled_card_${schedule.id}")
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = schedule.restaurantName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#${schedule.id}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = recurrenceSummary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = when (schedule.status) {
                            ScheduledOrderStatus.PLACING_ORDER -> "Placing order with restaurant now..."
                            ScheduledOrderStatus.COMPLETED -> "Placed ${schedule.completedOccurrences} order(s) with restaurant"
                            ScheduledOrderStatus.CANCELLED -> "Cancelled before placement ($nextSlotFormatted)"
                            else -> "$placementSentence ($nextSlotFormatted)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!schedule.lastFailureReason.isNullOrBlank() && schedule.status == ScheduledOrderStatus.FAILED) {
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
                        Column {
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

            Text(
                text = schedule.items.joinToString(", ") { "${it.quantity} × ${it.foodName}" },
                style = MaterialTheme.typography.bodyMedium,
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
                    text = "${schedule.paymentMethod.displayName} • ${schedule.completedOccurrences} placed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (schedule.status == ScheduledOrderStatus.UPCOMING) {
                    Button(
                        onClick = onRunNowDemo,
                        enabled = !isExecuting,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("run_now_schedule_${schedule.id}")
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
                        Text("Run Now")
                    }
                    OutlinedButton(
                        onClick = onPause,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("pause_schedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                }

                if (schedule.status == ScheduledOrderStatus.PAUSED ||
                    schedule.status == ScheduledOrderStatus.FAILED
                ) {
                    Button(
                        onClick = onResume,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("resume_schedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume")
                    }
                }

                if (schedule.status.canReschedule) {
                    OutlinedButton(
                        onClick = onReschedule,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("reschedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.EditCalendar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reschedule")
                    }
                }
                if (schedule.status.canCancel) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancel_schedule_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }

                schedule.lastGeneratedOrderId?.let { generatedId ->
                    OutlinedButton(
                        onClick = { onTrackLatestOrder(generatedId) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("track_generated_${schedule.id}")
                    ) {
                        Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Track Order")
                    }
                }

                TextButton(
                    onClick = onViewDetails,
                    modifier = Modifier.testTag("history_schedule_${schedule.id}")
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("History")
                }
            }
        }
    }
}

/** Shows schedule details, execution history, and order-tracking actions. */
@Composable
private fun ScheduledOrderDetailsDialog(
    schedule: ScheduledOrder,
    recurrenceSummary: String,
    executions: List<ScheduledOrderExecution>,
    onTrackOrder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(schedule.restaurantName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "#${schedule.id} • $recurrenceSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delivery Address", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = schedule.deliveryAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Price policy: ${schedule.priceConsentPolicy.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider()
                Text(
                    text = "Execution History (${executions.size})",
                    style = MaterialTheme.typography.labelLarge
                )
                if (executions.isEmpty()) {
                    Text(
                        text = "No occurrences executed yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    executions.take(6).forEach { exec ->
                        val dateText = remember(exec.executedAtUtcMs) {
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(exec.executedAtUtcMs))
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Occurrence #${exec.occurrenceNumber} • ${exec.status.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (exec.status == ScheduleExecutionStatus.SUCCEEDED && exec.generatedOrderId != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Order placed • ${exec.quotedTotalPayable.toInr()}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(
                                            onClick = { onTrackOrder(exec.generatedOrderId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Track")
                                        }
                                    }
                                } else if (!exec.failureReason.isNullOrBlank()) {
                                    Text(
                                        text = "${exec.failureCategory.label}: ${exec.failureReason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Lets the user choose a new placement date and time for a schedule. */
@Composable
private fun RescheduleDialog(
    schedule: ScheduledOrder,
    draftUtcMs: Long,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
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
                    text = "Pick a new future date and time to place your order with ${schedule.restaurantName}.",
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
                modifier = Modifier.testTag("confirm_reschedule_button")
            ) {
                Text("Save Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
