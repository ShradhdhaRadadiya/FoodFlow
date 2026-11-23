package com.foodflow.feature.scheduled

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderExecution
import com.foodflow.domain.model.ScheduledOrderFailurePopup
import com.foodflow.domain.model.ScheduledOrderStatus

enum class ScheduledOrdersTab(val label: String) {
    UPCOMING("Scheduled"),
    COMPLETED("Placed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

data class ScheduledOrdersUiState(
    val schedules: List<ScheduledOrder> = emptyList(),
    val pendingFailurePopups: List<ScheduledOrderFailurePopup> = emptyList(),
    val selectedTab: ScheduledOrdersTab = ScheduledOrdersTab.UPCOMING,
    val detailSchedule: ScheduledOrder? = null,
    val detailExecutionHistory: List<ScheduledOrderExecution> = emptyList(),
    val rescheduleTarget: ScheduledOrder? = null,
    val rescheduleDraftUtcMs: Long = 0L,
    val showRescheduleDatePicker: Boolean = false,
    val showRescheduleTimePicker: Boolean = false,
    val executingScheduleId: String? = null,
    val isReconciling: Boolean = false,
    val confirmedSchedule: ScheduledOrder? = null,
    val isOffline: Boolean = false
) : MviState {

    val activeFailurePopup: ScheduledOrderFailurePopup?
        get() = pendingFailurePopups.firstOrNull()

    val upcomingSchedules: List<ScheduledOrder>
        get() = schedules.filter {
            it.status == ScheduledOrderStatus.UPCOMING ||
                it.status == ScheduledOrderStatus.PLACING_ORDER ||
                it.status == ScheduledOrderStatus.PAUSED
        }

    val completedSchedules: List<ScheduledOrder>
        get() = schedules.filter { it.status == ScheduledOrderStatus.COMPLETED }

    val failedSchedules: List<ScheduledOrder>
        get() = schedules.filter { it.status == ScheduledOrderStatus.FAILED }

    val cancelledSchedules: List<ScheduledOrder>
        get() = schedules.filter { it.status == ScheduledOrderStatus.CANCELLED }

    val visibleSchedules: List<ScheduledOrder>
        get() = when (selectedTab) {
            ScheduledOrdersTab.UPCOMING -> upcomingSchedules
            ScheduledOrdersTab.COMPLETED -> completedSchedules
            ScheduledOrdersTab.FAILED -> failedSchedules
            ScheduledOrdersTab.CANCELLED -> cancelledSchedules
        }
}

sealed interface ScheduledOrdersIntent : MviIntent {
    data class LoadConfirmation(val scheduleId: String) : ScheduledOrdersIntent
    data class SelectTab(val tab: ScheduledOrdersTab) : ScheduledOrdersIntent
    data class Pause(val scheduleId: String) : ScheduledOrdersIntent
    data class Resume(val scheduleId: String) : ScheduledOrdersIntent
    data class Cancel(val scheduleId: String) : ScheduledOrdersIntent
    data class OpenDetails(val schedule: ScheduledOrder) : ScheduledOrdersIntent
    data object DismissDetails : ScheduledOrdersIntent
    data class OpenReschedule(val schedule: ScheduledOrder) : ScheduledOrdersIntent
    data object DismissReschedule : ScheduledOrdersIntent
    data object OpenRescheduleDatePicker : ScheduledOrdersIntent
    data object DismissRescheduleDatePicker : ScheduledOrdersIntent
    data class ConfirmRescheduleDate(val utcDateMillis: Long) : ScheduledOrdersIntent
    data object OpenRescheduleTimePicker : ScheduledOrdersIntent
    data object DismissRescheduleTimePicker : ScheduledOrdersIntent
    data class ConfirmRescheduleTime(val hourOfDay: Int, val minute: Int) : ScheduledOrdersIntent
    data object SaveReschedule : ScheduledOrdersIntent
    data class TriggerRunNowForDemo(val scheduleId: String) : ScheduledOrdersIntent
    data object RunOverdueReconciliation : ScheduledOrdersIntent
    data class AcknowledgeFailurePopup(val scheduleId: String) : ScheduledOrdersIntent
    data class HandleFailurePrimaryAction(val popup: ScheduledOrderFailurePopup) : ScheduledOrdersIntent
    data class HandleFailureCancelOrder(val popup: ScheduledOrderFailurePopup) : ScheduledOrdersIntent
}

sealed interface ScheduledOrdersEffect : MviEffect {
    data class ShowMessage(val message: String) : ScheduledOrdersEffect
    data class NavigateToTrackOrder(val orderId: String) : ScheduledOrdersEffect
    data class NavigateToRestaurant(val restaurantId: String) : ScheduledOrdersEffect
}
