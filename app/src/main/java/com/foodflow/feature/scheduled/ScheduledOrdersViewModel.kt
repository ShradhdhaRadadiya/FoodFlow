package com.foodflow.feature.scheduled

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduledFailureAction
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderFailurePopup
import com.foodflow.domain.usecase.scheduled.AcknowledgeScheduledFailureUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.CancelScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.ExecuteScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.domain.usecase.scheduled.GetScheduleExecutionHistoryUseCase
import com.foodflow.domain.usecase.scheduled.GetScheduledOrdersUseCase
import com.foodflow.domain.usecase.scheduled.ObserveScheduledFailureAlertsUseCase
import com.foodflow.domain.usecase.scheduled.ObserveScheduledOrderDetailUseCase
import com.foodflow.domain.usecase.scheduled.PauseScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.scheduled.ResumeScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.UpdateScheduledOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class ScheduledOrdersViewModel @Inject constructor(
    getScheduledOrders: GetScheduledOrdersUseCase,
    observeScheduledFailureAlerts: ObserveScheduledFailureAlertsUseCase,
    private val observeScheduledOrderDetail: ObserveScheduledOrderDetailUseCase,
    private val getExecutionHistory: GetScheduleExecutionHistoryUseCase,
    private val pauseScheduledOrder: PauseScheduledOrderUseCase,
    private val resumeScheduledOrder: ResumeScheduledOrderUseCase,
    private val updateScheduledOrder: UpdateScheduledOrderUseCase,
    private val cancelScheduledOrder: CancelScheduledOrderUseCase,
    private val executeScheduledOrder: ExecuteScheduledOrderUseCase,
    private val reconcileOverdueSchedules: ReconcileOverdueSchedulesUseCase,
    private val acknowledgeScheduledFailure: AcknowledgeScheduledFailureUseCase,
    private val calculateNextOccurrence: CalculateNextScheduleOccurrenceUseCase,
    private val formatScheduledPlacement: FormatScheduledPlacementUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<ScheduledOrdersUiState, ScheduledOrdersIntent, ScheduledOrdersEffect>(
    ScheduledOrdersUiState()
) {

    private var detailHistoryJob: Job? = null
    private var confirmationJob: Job? = null
    private var reconcileJob: Job? = null

    init {
        combine(
            getScheduledOrders(),
            observeScheduledFailureAlerts(),
            connectivityObserver.isOnline
        ) { schedules, failurePopups, online ->
            updateState {
                val refreshedDetail = detailSchedule?.let { current ->
                    schedules.firstOrNull { it.id == current.id } ?: current
                }
                copy(
                    schedules = schedules,
                    pendingFailurePopups = failurePopups,
                    detailSchedule = refreshedDetail,
                    isOffline = !online
                )
            }
        }.launchIn(viewModelScope)
    }

    /** Formats a schedule recurrence rule as a short summary for the screen. */
    fun formatRuleSummary(rule: ScheduleRecurrenceRule): String =
        calculateNextOccurrence.formatRecurrenceSummary(rule)

    /** Formats when the restaurant is scheduled to receive the order. */
    fun formatPlacementSentence(schedule: ScheduledOrder): String =
        formatScheduledPlacement.formatConfirmationSentence(
            placementUtcMs = schedule.nextExecutionAtUtcMs,
            timezoneId = schedule.recurrenceRule.timezoneId
        )

    /** Handles schedule history actions such as pause, resume, cancel, and reschedule. */
    override fun onIntent(intent: ScheduledOrdersIntent) {
        when (intent) {
            is ScheduledOrdersIntent.LoadConfirmation -> observeConfirmation(intent.scheduleId)
            is ScheduledOrdersIntent.SelectTab -> updateState { copy(selectedTab = intent.tab) }
            is ScheduledOrdersIntent.Pause -> pause(intent.scheduleId)
            is ScheduledOrdersIntent.Resume -> resume(intent.scheduleId)
            is ScheduledOrdersIntent.Cancel -> cancel(intent.scheduleId)
            is ScheduledOrdersIntent.OpenDetails -> openDetails(intent.schedule.id)
            ScheduledOrdersIntent.DismissDetails -> {
                detailHistoryJob?.cancel()
                updateState { copy(detailSchedule = null, detailExecutionHistory = emptyList()) }
            }
            is ScheduledOrdersIntent.OpenReschedule -> {
                val minFuture = System.currentTimeMillis() + 3_600_000L
                updateState {
                    copy(
                        rescheduleTarget = intent.schedule,
                        rescheduleDraftUtcMs = maxOf(intent.schedule.nextExecutionAtUtcMs, minFuture),
                        showRescheduleDatePicker = false,
                        showRescheduleTimePicker = false
                    )
                }
            }
            ScheduledOrdersIntent.DismissReschedule -> updateState {
                copy(
                    rescheduleTarget = null,
                    showRescheduleDatePicker = false,
                    showRescheduleTimePicker = false
                )
            }
            ScheduledOrdersIntent.OpenRescheduleDatePicker -> updateState {
                copy(showRescheduleDatePicker = true)
            }
            ScheduledOrdersIntent.DismissRescheduleDatePicker -> updateState {
                copy(showRescheduleDatePicker = false)
            }
            is ScheduledOrdersIntent.ConfirmRescheduleDate -> applyRescheduleDate(intent.utcDateMillis)
            ScheduledOrdersIntent.OpenRescheduleTimePicker -> updateState {
                copy(showRescheduleTimePicker = true)
            }
            ScheduledOrdersIntent.DismissRescheduleTimePicker -> updateState {
                copy(showRescheduleTimePicker = false)
            }
            is ScheduledOrdersIntent.ConfirmRescheduleTime -> applyRescheduleTime(intent.hourOfDay, intent.minute)
            ScheduledOrdersIntent.SaveReschedule -> saveReschedule()
            is ScheduledOrdersIntent.TriggerRunNowForDemo -> runNowForDemo(intent.scheduleId)
            ScheduledOrdersIntent.RunOverdueReconciliation -> runReconciliation()
            is ScheduledOrdersIntent.AcknowledgeFailurePopup -> viewModelScope.launch {
                acknowledgeScheduledFailure(intent.scheduleId)
            }
            is ScheduledOrdersIntent.HandleFailurePrimaryAction -> handleFailurePrimary(intent.popup)
            is ScheduledOrdersIntent.HandleFailureCancelOrder -> viewModelScope.launch {
                acknowledgeScheduledFailure(intent.popup.scheduleId)
                cancel(intent.popup.scheduleId)
            }
        }
    }

    /** Opens the next step requested by the scheduled-order failure alert. */
    private fun handleFailurePrimary(popup: ScheduledOrderFailurePopup) {
        viewModelScope.launch {
            acknowledgeScheduledFailure(popup.scheduleId)
            when (popup.primaryAction) {
                ScheduledFailureAction.RESCHEDULE -> {
                    val minFuture = System.currentTimeMillis() + 3_600_000L
                    updateState {
                        copy(
                            rescheduleTarget = popup.schedule,
                            rescheduleDraftUtcMs = maxOf(popup.schedule.nextExecutionAtUtcMs, minFuture),
                            showRescheduleDatePicker = false,
                            showRescheduleTimePicker = false
                        )
                    }
                }
                ScheduledFailureAction.REVIEW_ORDER -> {
                    emitEffect(ScheduledOrdersEffect.NavigateToRestaurant(popup.restaurantId))
                }
                ScheduledFailureAction.CANCEL_ORDER -> cancel(popup.scheduleId)
            }
        }
    }

    /** Observes the saved schedule so its confirmation screen stays up to date. */
    private fun observeConfirmation(scheduleId: String) {
        confirmationJob?.cancel()
        confirmationJob = observeScheduledOrderDetail(scheduleId)
            .onEach { schedule ->
                updateState { copy(confirmedSchedule = schedule) }
            }
            .launchIn(viewModelScope)
    }

    /** Opens schedule details and observes its execution history. */
    private fun openDetails(scheduleId: String) {
        val schedule = state.value.schedules.firstOrNull { it.id == scheduleId } ?: return
        updateState { copy(detailSchedule = schedule) }
        detailHistoryJob?.cancel()
        detailHistoryJob = getExecutionHistory(scheduleId)
            .onEach { history ->
                updateState { copy(detailExecutionHistory = history) }
            }
            .launchIn(viewModelScope)
    }

    /** Pauses future executions of the selected scheduled order. */
    private fun pause(scheduleId: String) {
        viewModelScope.launch {
            when (val res = pauseScheduledOrder(scheduleId)) {
                is AppResult.Success -> emitEffect(ScheduledOrdersEffect.ShowMessage("Scheduled order paused."))
                is AppResult.Error -> emitEffect(ScheduledOrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Resumes future executions of a paused scheduled order. */
    private fun resume(scheduleId: String) {
        viewModelScope.launch {
            when (val res = resumeScheduledOrder(scheduleId)) {
                is AppResult.Success -> emitEffect(ScheduledOrdersEffect.ShowMessage("Scheduled order resumed."))
                is AppResult.Error -> emitEffect(ScheduledOrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Cancels the selected scheduled order. */
    private fun cancel(scheduleId: String) {
        viewModelScope.launch {
            when (val res = cancelScheduledOrder(scheduleId, "Cancelled by customer")) {
                is AppResult.Success -> emitEffect(ScheduledOrdersEffect.ShowMessage("Scheduled order cancelled."))
                is AppResult.Error -> emitEffect(ScheduledOrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Updates the reschedule date while preserving the currently chosen time. */
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
            copy(
                rescheduleDraftUtcMs = merged.timeInMillis,
                showRescheduleDatePicker = false
            )
        }
    }

    /** Updates the reschedule time while preserving the currently chosen date. */
    private fun applyRescheduleTime(hourOfDay: Int, minute: Int) {
        val merged = Calendar.getInstance().apply {
            timeInMillis = state.value.rescheduleDraftUtcMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateState {
            copy(
                rescheduleDraftUtcMs = merged.timeInMillis,
                showRescheduleTimePicker = false
            )
        }
    }

    /** Saves the edited placement time and recurrence rule. */
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
                    emitEffect(ScheduledOrdersEffect.ShowMessage("Schedule updated successfully."))
                }
                is AppResult.Error -> emitEffect(ScheduledOrdersEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Runs the demo action that executes a scheduled order immediately. */
    private fun runNowForDemo(scheduleId: String) {
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
                                ScheduledOrdersEffect.ShowMessage(
                                    "Scheduled order placed! Tracking #${outcome.generatedOrder.id}."
                                )
                            )
                            emitEffect(ScheduledOrdersEffect.NavigateToTrackOrder(outcome.generatedOrder.id))
                        }
                        is ScheduleExecutionOutcome.Failed -> {
                            emitEffect(
                                ScheduledOrdersEffect.ShowMessage(
                                    outcome.execution.failureReason ?: "Scheduled execution failed validation."
                                )
                            )
                        }
                        is ScheduleExecutionOutcome.Skipped -> {
                            emitEffect(ScheduledOrdersEffect.ShowMessage(outcome.reason))
                        }
                    }
                }
                is AppResult.Error -> {
                    updateState { copy(executingScheduleId = null) }
                    emitEffect(ScheduledOrdersEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }

    /** Checks for overdue schedules and reports how many were processed. */
    private fun runReconciliation() {
        if (state.value.isReconciling || reconcileJob?.isActive == true) return
        if (!connectivityObserver.isCurrentlyOnline()) {
            viewModelScope.launch {
                emitEffect(
                    ScheduledOrdersEffect.ShowMessage("You're offline. Showing saved scheduled orders.")
                )
            }
            return
        }
        reconcileJob = viewModelScope.launch {
            updateState { copy(isReconciling = true) }
            try {
                val count = reconcileOverdueSchedules()
                val msg = if (count > 0) {
                    "Checked and processed $count due scheduled order(s)."
                } else {
                    "All upcoming schedules are up to date."
                }
                emitEffect(ScheduledOrdersEffect.ShowMessage(msg))
            } catch (_: Throwable) {
                emitEffect(ScheduledOrdersEffect.ShowMessage("Couldn't refresh scheduled orders right now."))
            } finally {
                updateState { copy(isReconciling = false) }
            }
        }
    }
}
