package com.foodflow.feature.checkout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.DeliveryLocationMapPicker
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.PriceBreakdownCard
import com.foodflow.core.designsystem.components.foodFlowBottomBarInsets
import com.foodflow.core.designsystem.components.foodFlowKeyboardAndNavInsets
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.domain.model.CheckoutOrderMode
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    couponCode: String?,
    onBack: () -> Unit,
    onChangeAddress: () -> Unit,
    onNavigateToPayment: (orderId: String, idempotencyKey: String) -> Unit,
    onNavigateToScheduleConfirmation: (scheduleId: String) -> Unit = {},
    viewModel: CheckoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(couponCode) {
        viewModel.onIntent(CheckoutIntent.Initialize(couponCode))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is CheckoutEffect.NavigateToPayment -> onNavigateToPayment(effect.orderId, effect.idempotencyKey)
                is CheckoutEffect.NavigateToScheduleConfirmation -> onNavigateToScheduleConfirmation(effect.scheduleId)
                is CheckoutEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val isScheduledMode = state.orderMode == CheckoutOrderMode.SCHEDULE_ORDER

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Checkout",
                subtitle = state.restaurant?.name,
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .foodFlowBottomBarInsets()
                        .padding(FoodFlowSpacing.md)
                ) {
                    Button(
                        onClick = { viewModel.onIntent(CheckoutIntent.PlaceOrder) },
                        enabled = !state.isSubmitting &&
                            state.items.isNotEmpty() &&
                            (!isScheduledMode || state.scheduleValidationErrors.isEmpty()),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("checkout_place_order_button")
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(if (isScheduledMode) "Confirming schedule..." else "Verifying order...")
                        } else {
                            val ctaLabel = if (isScheduledMode) {
                                "Confirm Schedule · ${state.breakdown.totalPayable.toInr()}"
                            } else {
                                "Place Order · ${state.breakdown.totalPayable.toInr()}"
                            }
                            Text(
                                text = ctaLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.md)
        ) {
            if (state.isOffline) {
                item(key = "offline_banner") {
                    OfflineBanner(isOffline = true)
                }
            }

            state.errorMessage?.let { err ->
                item(key = "error_banner") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag("checkout_error_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(onClick = { viewModel.onIntent(CheckoutIntent.PlaceOrder) }) {
                                Text("Try Again")
                            }
                        }
                    }
                }
            }

            item(key = "delivery_address") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Deliver to ${state.selectedAddress?.label?.displayName ?: "Address"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = state.selectedAddress?.let { "${it.line1}, ${it.city} - ${it.pincode}" }
                                        ?: "No address selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                state.selectedAddress?.deliveryInstructions?.takeIf { it.isNotBlank() }?.let { note ->
                                    Text(
                                        text = "Note: $note",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.onIntent(CheckoutIntent.OpenQuickMapSheet) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("checkout_adjust_map_button")
                            ) {
                                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Adjust on Map")
                            }
                            TextButton(
                                onClick = onChangeAddress,
                                modifier = Modifier.testTag("checkout_change_address")
                            ) {
                                Text("Saved List")
                            }
                        }
                    }
                }
            }

            item(key = "delivery_timing_selector") {
                DeliveryTimingCard(
                    state = state,
                    onIntent = viewModel::onIntent
                )
            }

            item(key = "order_summary") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Order Summary (${state.items.sumOf { it.quantity }} items)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isScheduledMode && state.scheduleConfirmationSentence.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("checkout_summary_schedule_badge")
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
                                        text = state.scheduleConfirmationSentence,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        state.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${item.quantity} × ${item.foodName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(item.lineTotal.toInr(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            item(key = "payment_method") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Payment Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PaymentMethod.entries.forEach { method ->
                            val selected = state.selectedPaymentMethod == method
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onIntent(CheckoutIntent.SelectPaymentMethod(method)) }
                                    .padding(vertical = 6.dp)
                                    .testTag("payment_method_${method.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { viewModel.onIntent(CheckoutIntent.SelectPaymentMethod(method)) }
                                )
                                Column(modifier = Modifier.padding(start = 6.dp)) {
                                    Text(method.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = method.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bill_breakdown") {
                PriceBreakdownCard(breakdown = state.breakdown)
            }

            if (isScheduledMode) {
                item(key = "order_protection_note") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Item availability, restaurant hours, and prices are confirmed automatically before your scheduled order is placed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (state.showDatePicker) {
        val todayStartUtcMs = remember {
            val localNow = Calendar.getInstance()
            Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(
                    localNow.get(Calendar.YEAR),
                    localNow.get(Calendar.MONTH),
                    localNow.get(Calendar.DAY_OF_MONTH)
                )
            }.timeInMillis
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = maxOf(state.scheduledAtUtcMs, todayStartUtcMs),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayStartUtcMs
            }
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.onIntent(CheckoutIntent.DismissDatePicker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            viewModel.onIntent(CheckoutIntent.ConfirmDateSelected(it))
                        } ?: viewModel.onIntent(CheckoutIntent.DismissDatePicker)
                    }
                ) { Text("Select") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(CheckoutIntent.DismissDatePicker) }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = "Select Date",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                }
            )
        }
    }

    if (state.showTimePicker) {
        val cal = remember(state.scheduledAtUtcMs) {
            Calendar.getInstance().apply { timeInMillis = state.scheduledAtUtcMs }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(CheckoutIntent.DismissTimePicker) },
            title = { Text("Select Order Placement Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onIntent(
                            CheckoutIntent.ConfirmTimeSelected(
                                hourOfDay = timePickerState.hour,
                                minute = timePickerState.minute
                            )
                        )
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(CheckoutIntent.DismissTimePicker) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (state.showEndDatePicker) {
        val endPickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.endDateUtcMs ?: (state.scheduledAtUtcMs + 30L * 86_400_000L)
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.onIntent(CheckoutIntent.DismissEndDatePicker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        endPickerState.selectedDateMillis?.let {
                            viewModel.onIntent(CheckoutIntent.ConfirmEndDateSelected(it))
                        } ?: viewModel.onIntent(CheckoutIntent.DismissEndDatePicker)
                    }
                ) { Text("Select") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(CheckoutIntent.DismissEndDatePicker) }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = endPickerState)
        }
    }

    if (state.showQuickMapSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.onIntent(CheckoutIntent.DismissQuickMapSheet) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FoodFlowSpacing.md, vertical = FoodFlowSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Adjust Delivery Pin",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                DeliveryLocationMapPicker(
                    initialLatitude = state.selectedAddress?.latitude ?: 23.0258,
                    initialLongitude = state.selectedAddress?.longitude ?: 72.5293,
                    initialAddressLine = state.selectedAddress?.line1.orEmpty(),
                    confirmButtonLabel = "Confirm Delivery Pin",
                    onConfirmPin = { pin -> viewModel.onIntent(CheckoutIntent.ConfirmAdjustedPin(pin)) },
                    onLocationFeedback = { msg ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    mapHeight = 230.dp
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryTimingCard(
    state: CheckoutUiState,
    onIntent: (CheckoutIntent) -> Unit
) {
    val dateText = remember(state.scheduledAtUtcMs) {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(Date(state.scheduledAtUtcMs))
    }
    val timeText = remember(state.scheduledAtUtcMs) {
        SimpleDateFormat("hh:mm a", Locale.US).format(Date(state.scheduledAtUtcMs))
    }
    val endDateText = remember(state.endDateUtcMs) {
        state.endDateUtcMs?.let {
            SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(it))
        } ?: "Pick end date"
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Order Timing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (state.orderMode == CheckoutOrderMode.SCHEDULE_ORDER) {
                        "Choose when FoodFlow should place your order with the restaurant"
                    } else {
                        "Place your order immediately with the restaurant"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CheckoutOrderMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.orderMode == mode,
                        onClick = { onIntent(CheckoutIntent.SelectOrderMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CheckoutOrderMode.entries.size
                        ),
                        icon = {
                            Icon(
                                imageVector = if (mode == CheckoutOrderMode.ORDER_NOW) {
                                    Icons.Filled.Bolt
                                } else {
                                    Icons.Filled.Schedule
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("checkout_mode_${mode.name.lowercase()}")
                    ) {
                        Text(mode.label, maxLines = 1)
                    }
                }
            }

            AnimatedVisibility(visible = state.orderMode == CheckoutOrderMode.SCHEDULE_ORDER) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Recurrence type chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                text = "Frequency",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ScheduleRecurrenceType.entries.forEach { type ->
                                FilterChip(
                                    selected = state.recurrenceType == type,
                                    onClick = { onIntent(CheckoutIntent.SelectRecurrenceType(type)) },
                                    label = { Text(type.label) },
                                    modifier = Modifier.testTag("schedule_freq_${type.name.lowercase()}")
                                )
                            }
                        }
                    }

                    // Date & Order Placement Time selection buttons
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Select Date & Order Placement Time",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onIntent(CheckoutIntent.OpenDatePicker) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("schedule_pick_date_button")
                            ) {
                                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(dateText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { onIntent(CheckoutIntent.OpenTimePicker) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(0.85f)
                                    .testTag("schedule_pick_time_button")
                            ) {
                                Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(timeText, maxLines = 1)
                            }
                        }
                    }

                    // Weekly multi-day picker
                    if (state.recurrenceType == ScheduleRecurrenceType.WEEKLY) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Repeat on days",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            val weekdays = listOf(
                                Calendar.MONDAY to "Mon",
                                Calendar.TUESDAY to "Tue",
                                Calendar.WEDNESDAY to "Wed",
                                Calendar.THURSDAY to "Thu",
                                Calendar.FRIDAY to "Fri",
                                Calendar.SATURDAY to "Sat",
                                Calendar.SUNDAY to "Sun"
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                weekdays.forEach { (dayConst, label) ->
                                    FilterChip(
                                        selected = dayConst in state.selectedWeekdays,
                                        onClick = { onIntent(CheckoutIntent.ToggleWeekday(dayConst)) },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("schedule_weekday_${label.lowercase()}")
                                    )
                                }
                            }
                        }
                    }

                    // Monthly day-of-month selector
                    if (state.recurrenceType == ScheduleRecurrenceType.MONTHLY) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Day of every month",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Short months (e.g., Feb) clamp automatically to month-end.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onIntent(CheckoutIntent.SetDayOfMonth(state.selectedDayOfMonth - 1)) },
                                    enabled = state.selectedDayOfMonth > 1
                                ) {
                                    Icon(Icons.Filled.Remove, contentDescription = "Decrease day")
                                }
                                Text(
                                    text = "${state.selectedDayOfMonth}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { onIntent(CheckoutIntent.SetDayOfMonth(state.selectedDayOfMonth + 1)) },
                                    enabled = state.selectedDayOfMonth < 31
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Increase day")
                                }
                            }
                        }
                    }

                    // Recurring end condition
                    if (state.recurrenceType != ScheduleRecurrenceType.ONE_TIME) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Ends",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ScheduleEndConditionType.entries.forEach { endType ->
                                    FilterChip(
                                        selected = state.endConditionType == endType,
                                        onClick = { onIntent(CheckoutIntent.SelectEndCondition(endType)) },
                                        label = { Text(endType.label) }
                                    )
                                }
                            }
                            if (state.endConditionType == ScheduleEndConditionType.ON_DATE) {
                                OutlinedButton(
                                    onClick = { onIntent(CheckoutIntent.OpenEndDatePicker) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Until $endDateText")
                                }
                            } else if (state.endConditionType == ScheduleEndConditionType.AFTER_OCCURRENCES) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Total scheduled orders:", style = MaterialTheme.typography.bodySmall)
                                    IconButton(
                                        onClick = { onIntent(CheckoutIntent.SetMaxOccurrences(state.maxOccurrences - 1)) },
                                        enabled = state.maxOccurrences > 1
                                    ) {
                                        Icon(Icons.Filled.Remove, contentDescription = "Fewer occurrences")
                                    }
                                    Text(
                                        text = "${state.maxOccurrences}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { onIntent(CheckoutIntent.SetMaxOccurrences(state.maxOccurrences + 1)) },
                                        enabled = state.maxOccurrences < 100
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "More occurrences")
                                    }
                                }
                            }
                        }
                    }

                    // Price protection policy
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Price Protection",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SchedulePriceConsentPolicy.entries.forEach { policy ->
                                FilterChip(
                                    selected = state.priceConsentPolicy == policy,
                                    onClick = { onIntent(CheckoutIntent.SelectPriceConsentPolicy(policy)) },
                                    label = { Text(policy.label) }
                                )
                            }
                        }
                    }

                    // Schedule placement confirmation & operating hours pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (state.scheduleConfirmationSentence.isNotBlank()) {
                                Text(
                                    text = state.scheduleConfirmationSentence,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.testTag("checkout_schedule_confirmation_sentence")
                                )
                            }
                            Text(
                                text = state.scheduleSummaryLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("checkout_schedule_summary")
                            )
                            Text(
                                text = "Preparation & delivery begin after order placement • Kitchen hours: ${state.operatingHours.formattedWindow()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Live validation error list
                    if (state.scheduleValidationErrors.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_validation_errors")
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    state.scheduleValidationErrors.forEach { err ->
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
