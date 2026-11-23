package com.foodflow.feature.orders

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.RestaurantRatingCalculator
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduledFailureAction
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderFailurePopup
import com.foodflow.domain.model.ScheduledOrderStatus
import com.foodflow.domain.usecase.order.GenerateOrderInvoiceUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.order.RefreshOrdersUseCase
import com.foodflow.domain.usecase.order.ReorderUseCase
import com.foodflow.domain.usecase.preferences.ObserveUserPreferencesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.review.SubmitRestaurantReviewUseCase
import com.foodflow.domain.usecase.review.UpdateRestaurantReviewUseCase
import com.foodflow.domain.usecase.scheduled.AcknowledgeScheduledFailureUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.CancelScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.ExecuteScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.domain.usecase.scheduled.GetScheduledOrdersUseCase
import com.foodflow.domain.usecase.scheduled.ObserveScheduledFailureAlertsUseCase
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.scheduled.UpdateScheduledOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(
    observeOrders: ObserveOrdersUseCase,
    observeRestaurants: ObserveRestaurantsUseCase,
    getScheduledOrders: GetScheduledOrdersUseCase,
    observeScheduledFailureAlerts: ObserveScheduledFailureAlertsUseCase,
    private val observeUserPreferences: ObserveUserPreferencesUseCase,
    private val refreshOrders: RefreshOrdersUseCase,
    private val reorderUseCase: ReorderUseCase,
    private val submitRestaurantReview: SubmitRestaurantReviewUseCase,
    private val updateRestaurantReview: UpdateRestaurantReviewUseCase,
    private val generateOrderInvoice: GenerateOrderInvoiceUseCase,
    private val cancelScheduledOrder: CancelScheduledOrderUseCase,
    private val updateScheduledOrder: UpdateScheduledOrderUseCase,
    private val executeScheduledOrder: ExecuteScheduledOrderUseCase,
    private val acknowledgeScheduledFailure: AcknowledgeScheduledFailureUseCase,
    private val reconcileOverdueSchedules: ReconcileOverdueSchedulesUseCase,
    private val calculateNextOccurrence: CalculateNextScheduleOccurrenceUseCase,
    private val formatScheduledPlacement: FormatScheduledPlacementUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<OrdersUiState, OrdersIntent, OrdersEffect>(OrdersUiState()) {

    private var hasAutoSelectedInitialTab = false
    private var refreshJob: Job? = null

    init {
        combine(
            observeOrders(),
            observeRestaurants(),
            getScheduledOrders(),
            observeScheduledFailureAlerts(),
            connectivityObserver.isOnline
        ) { orders, restaurants, schedules, failurePopups, online ->
            val imageMap = restaurants.associate { it.id to it.imageKey }
            val upcomingSchedules = schedules.count {
                it.status == ScheduledOrderStatus.UPCOMING || it.status == ScheduledOrderStatus.PLACING_ORDER
            }
            val nextTab = if (!hasAutoSelectedInitialTab && (orders.isNotEmpty() || schedules.isNotEmpty())) {
                hasAutoSelectedInitialTab = true
                when {
                    orders.any { it.orderStatus.isActive } -> OrdersTab.ACTIVE
                    schedules.any {
                        it.status == ScheduledOrderStatus.UPCOMING ||
                            it.status == ScheduledOrderStatus.PLACING_ORDER ||
                            it.status == ScheduledOrderStatus.FAILED
                    } -> OrdersTab.SCHEDULED
                    else -> OrdersTab.COMPLETED
                }
            } else {
                state.value.selectedTab
            }
            updateState {
                val refreshedScheduledDetail = detailsScheduledOrder?.let { current ->
                    schedules.firstOrNull { it.id == current.id } ?: current
                }
                copy(
                    orders = orders,
                    scheduledOrders = schedules,
                    pendingFailurePopups = failurePopups,
                    restaurantImageById = imageMap,
                    selectedTab = nextTab,
                    upcomingScheduledCount = upcomingSchedules,
                    detailsScheduledOrder = refreshedScheduledDetail,
                    isOffline = !online
                )
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            runCatching { reconcileOverdueSchedules() }
            if (connectivityObserver.isCurrentlyOnline()) {
                refreshOrders()
            }
        }
    }

    /** Formats the schedule's next order-placement time as a confirmation sentence. */
    fun formatScheduleSentence(schedule: ScheduledOrder): String =
        formatScheduledPlacement.formatConfirmationSentence(
            placementUtcMs = schedule.nextExecutionAtUtcMs,
            timezoneId = schedule.recurrenceRule.timezoneId
        )

    /** Formats the schedule's next order-placement date and time for display. */
    fun formatScheduleDateTime(schedule: ScheduledOrder): String =
        formatScheduledPlacement.formatDateTimeSummary(
            placementUtcMs = schedule.nextExecutionAtUtcMs,
            timezoneId = schedule.recurrenceRule.timezoneId
        )

    /** Converts a recurrence rule into a short readable schedule summary. */
    fun formatRecurrenceSummary(rule: ScheduleRecurrenceRule): String =
        calculateNextOccurrence.formatRecurrenceSummary(rule)

    /** Handles order-history actions such as refresh, rating, invoice, and schedule changes. */
    override fun onIntent(intent: OrdersIntent) {
        when (intent) {
            OrdersIntent.Refresh -> refresh()
            is OrdersIntent.SelectTab -> updateState { copy(selectedTab = intent.tab) }
            is OrdersIntent.Reorder -> reorder(intent.orderId)
            is OrdersIntent.OpenRatingDialog -> {
                val target = state.value.orders.firstOrNull { it.id == intent.orderId }
                updateState {
                    copy(
                        ratingOrderId = intent.orderId,
                        ratingInitialStars = target?.ratingGiven ?: 5,
                        ratingInitialComment = target?.reviewComment.orEmpty(),
                        isEditingRating = target?.ratingGiven != null,
                        isSubmittingRating = false
                    )
                }
            }
            OrdersIntent.DismissRatingDialog -> updateState {
                copy(ratingOrderId = null, isSubmittingRating = false)
            }
            is OrdersIntent.SubmitRating -> submitRating(intent.orderId, intent.rating, intent.comment)
            is OrdersIntent.OpenOrderDetails -> updateState { copy(detailsOrder = intent.order) }
            OrdersIntent.DismissOrderDetails -> updateState { copy(detailsOrder = null) }
            is OrdersIntent.DownloadInvoice -> downloadInvoice(intent.order)
            OrdersIntent.DismissInvoiceReady -> updateState { copy(readyInvoice = null) }
            is OrdersIntent.OpenScheduledDetails -> updateState { copy(detailsScheduledOrder = intent.schedule) }
            OrdersIntent.DismissScheduledDetails -> updateState { copy(detailsScheduledOrder = null) }
            is OrdersIntent.CancelScheduledOrder -> cancelSchedule(intent.scheduleId)
            is OrdersIntent.OpenReschedule -> openRescheduleDialog(intent.schedule)
            OrdersIntent.DismissReschedule -> updateState {
                copy(
                    rescheduleTarget = null,
                    showRescheduleDatePicker = false,
                    showRescheduleTimePicker = false
                )
            }
            OrdersIntent.OpenRescheduleDatePicker -> updateState { copy(showRescheduleDatePicker = true) }
            OrdersIntent.DismissRescheduleDatePicker -> updateState { copy(showRescheduleDatePicker = false) }
            is OrdersIntent.ConfirmRescheduleDate -> applyRescheduleDate(intent.utcDateMillis)
            OrdersIntent.OpenRescheduleTimePicker -> updateState { copy(showRescheduleTimePicker = true) }
            OrdersIntent.DismissRescheduleTimePicker -> updateState { copy(showRescheduleTimePicker = false) }
            is OrdersIntent.ConfirmRescheduleTime -> applyRescheduleTime(intent.hourOfDay, intent.minute)
            OrdersIntent.SaveReschedule -> saveReschedule()
            is OrdersIntent.PlaceScheduledOrderNow -> placeScheduledNow(intent.scheduleId)
            is OrdersIntent.AcknowledgeFailurePopup -> acknowledgeFailure(intent.scheduleId)
            is OrdersIntent.HandleFailurePrimaryAction -> handleFailurePrimary(intent.popup)
            is OrdersIntent.HandleFailureCancelOrder -> handleFailureCancel(intent.popup)
        }
    }

    /** Refreshes orders and scheduled orders when the device is online. */
    private fun refresh() {
        if (state.value.isRefreshing || refreshJob?.isActive == true) return
        if (!connectivityObserver.isCurrentlyOnline()) {
            viewModelScope.launch {
                emitEffect(OrdersEffect.ShowMessage("You're offline. Showing cached orders."))
            }
            return
        }
        refreshJob = viewModelScope.launch {
            updateState { copy(isRefreshing = true) }
            try {
                runCatching { reconcileOverdueSchedules() }
                when (val res = refreshOrders()) {
                    is AppResult.Success -> Unit
                    is AppResult.Error -> emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
                }
            } catch (_: Throwable) {
                emitEffect(OrdersEffect.ShowMessage("Couldn't refresh orders right now."))
            } finally {
                updateState { copy(isRefreshing = false) }
            }
        }
    }

    /** Opens the reschedule flow with the schedule's next valid time preselected. */
    private fun openRescheduleDialog(schedule: ScheduledOrder) {
        if (!schedule.status.canReschedule) {
            viewModelScope.launch {
                emitEffect(
                    OrdersEffect.ShowMessage(
                        "This scheduled order is ${schedule.status.label.lowercase()} and can no longer be rescheduled."
                    )
                )
            }
            return
        }
        val minFuture = System.currentTimeMillis() + 3_600_000L
        updateState {
            copy(
                rescheduleTarget = schedule,
                rescheduleDraftUtcMs = maxOf(schedule.nextExecutionAtUtcMs, minFuture),
                showRescheduleDatePicker = false,
                showRescheduleTimePicker = false
            )
        }
    }

    /** Changes the reschedule date while preserving its selected local time. */
    private fun applyRescheduleDate(utcDateMillis: Long) {
        val currentLocal = Calendar.getInstance().apply { timeInMillis = state.value.rescheduleDraftUtcMs }
        val utcPicker = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcDateMillis
        }
        val merged = Calendar.getInstance().apply {
            set(Calendar.YEAR, utcPicker.get(Calendar.YEAR))
            set(Calendar.MONTH, utcPicker.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcPicker.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, currentLocal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, currentLocal.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateState {
            copy(rescheduleDraftUtcMs = merged.timeInMillis, showRescheduleDatePicker = false)
        }
    }

    /** Changes the reschedule time while preserving its selected date. */
    private fun applyRescheduleTime(hourOfDay: Int, minute: Int) {
        val merged = Calendar.getInstance().apply {
            timeInMillis = state.value.rescheduleDraftUtcMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateState {
            copy(rescheduleDraftUtcMs = merged.timeInMillis, showRescheduleTimePicker = false)
        }
    }

    /** Saves the updated schedule time and recurrence settings. */
    private fun saveReschedule() {
        val target = state.value.rescheduleTarget ?: return
        val draftMs = state.value.rescheduleDraftUtcMs
        val cal = Calendar.getInstance().apply { timeInMillis = draftMs }
        val updatedRule = target.recurrenceRule.copy(
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            dayOfMonth = target.recurrenceRule.dayOfMonth?.let { cal.get(Calendar.DAY_OF_MONTH) }
        )
        viewModelScope.launch {
            when (val res = updateScheduledOrder(target.id, draftMs, updatedRule)) {
                is AppResult.Success -> {
                    updateState { copy(rescheduleTarget = null) }
                    emitEffect(
                        OrdersEffect.ShowMessage(
                            formatScheduledPlacement.formatConfirmationSentence(
                                res.data.nextExecutionAtUtcMs,
                                res.data.recurrenceRule.timezoneId
                            )
                        )
                    )
                }
                is AppResult.Error -> emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Cancels the selected scheduled order and reports the result. */
    private fun cancelSchedule(scheduleId: String) {
        viewModelScope.launch {
            when (val res = cancelScheduledOrder(scheduleId, "Cancelled by customer")) {
                is AppResult.Success -> emitEffect(OrdersEffect.ShowMessage("Scheduled order cancelled."))
                is AppResult.Error -> emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Triggers the demo flow that places a scheduled order immediately. */
    private fun placeScheduledNow(scheduleId: String) {
        if (state.value.executingScheduleId != null) return
        viewModelScope.launch {
            updateState { copy(executingScheduleId = scheduleId) }
            when (
                val res = executeScheduledOrder(
                    scheduleId = scheduleId,
                    expectedOccurrenceNumber = null,
                    forceRunNowForDemo = true
                )
            ) {
                is AppResult.Success -> {
                    updateState { copy(executingScheduleId = null) }
                    when (val outcome = res.data) {
                        is ScheduleExecutionOutcome.Succeeded -> {
                            emitEffect(
                                OrdersEffect.ShowMessage(
                                    "Scheduled order placed with restaurant! Tracking #${outcome.generatedOrder.id}."
                                )
                            )
                            emitEffect(OrdersEffect.NavigateToTrackOrder(outcome.generatedOrder.id))
                        }
                        is ScheduleExecutionOutcome.Failed -> {
                            emitEffect(
                                OrdersEffect.ShowMessage(
                                    outcome.execution.failureReason ?: "Unable to place your scheduled order."
                                )
                            )
                        }
                        is ScheduleExecutionOutcome.Skipped -> {
                            emitEffect(OrdersEffect.ShowMessage(outcome.reason))
                        }
                    }
                }
                is AppResult.Error -> {
                    updateState { copy(executingScheduleId = null) }
                    emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }

    /** Marks the selected scheduled-order failure alert as acknowledged. */
    private fun acknowledgeFailure(scheduleId: String) {
        viewModelScope.launch {
            acknowledgeScheduledFailure(scheduleId)
        }
    }

    /** Runs the main action offered by a scheduled-order failure alert. */
    private fun handleFailurePrimary(popup: ScheduledOrderFailurePopup) {
        viewModelScope.launch {
            acknowledgeScheduledFailure(popup.scheduleId)
            when (popup.primaryAction) {
                ScheduledFailureAction.RESCHEDULE -> openRescheduleDialog(popup.schedule)
                ScheduledFailureAction.REVIEW_ORDER -> {
                    emitEffect(OrdersEffect.NavigateToRestaurant(popup.restaurantId))
                }
                ScheduledFailureAction.CANCEL_ORDER -> cancelSchedule(popup.scheduleId)
            }
        }
    }

    /** Acknowledges the failure alert and cancels its scheduled order. */
    private fun handleFailureCancel(popup: ScheduledOrderFailurePopup) {
        viewModelScope.launch {
            acknowledgeScheduledFailure(popup.scheduleId)
            cancelSchedule(popup.scheduleId)
        }
    }

    /** Adds items from a past order to the current cart. */
    private fun reorder(orderId: String) {
        viewModelScope.launch {
            when (val res = reorderUseCase(orderId)) {
                is AppResult.Success -> {
                    emitEffect(OrdersEffect.ShowMessage("Items added to your cart."))
                    emitEffect(OrdersEffect.NavigateToCart)
                }
                is AppResult.Error -> emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Saves a rating and comment for the selected completed order. */
    private fun submitRating(orderId: String, rating: Int, comment: String) {
        if (state.value.isSubmittingRating) return
        viewModelScope.launch {
            updateState { copy(isSubmittingRating = true) }
            val isEditing = state.value.isEditingRating
            val res = if (isEditing) {
                updateRestaurantReview(orderId, rating, comment)
            } else {
                submitRestaurantReview(orderId, rating, comment)
            }
            when (res) {
                is AppResult.Success -> {
                    val summary = res.data
                    val formatted = RestaurantRatingCalculator.formatRating(
                        summary.averageRating,
                        summary.totalReviewCount
                    )
                    updateState {
                        copy(ratingOrderId = null, isSubmittingRating = false)
                    }
                    val verb = if (isEditing) "Review updated!" else "Thanks for rating!"
                    emitEffect(
                        OrdersEffect.ShowMessage(
                            "$verb Restaurant rating is now $formatted ★ (${summary.totalReviewCount} reviews)."
                        )
                    )
                }
                is AppResult.Error -> {
                    updateState { copy(isSubmittingRating = false) }
                    emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }

    /** Generates the order invoice and reports where it was saved. */
    private fun downloadInvoice(order: Order) {
        viewModelScope.launch {
            updateState { copy(generatingInvoiceOrderId = order.id) }
            val prefs = observeUserPreferences().first()
            when (
                val res = generateOrderInvoice(
                    order = order,
                    customerName = prefs.userName,
                    customerPhone = prefs.userPhone
                )
            ) {
                is AppResult.Success -> {
                    updateState {
                        copy(generatingInvoiceOrderId = null, readyInvoice = res.data)
                    }
                    emitEffect(OrdersEffect.ShowMessage("Invoice ${res.data.fileName} saved to Downloads."))
                }
                is AppResult.Error -> {
                    updateState { copy(generatingInvoiceOrderId = null) }
                    emitEffect(OrdersEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }
}
