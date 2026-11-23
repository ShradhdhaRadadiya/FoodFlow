package com.foodflow.feature.orders

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.InvoiceDocument
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderFailurePopup
import com.foodflow.domain.model.ScheduledOrderStatus

enum class OrdersTab(val label: String) {
    ACTIVE("Active"),
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled")
}

data class OrdersUiState(
    val orders: List<Order> = emptyList(),
    val scheduledOrders: List<ScheduledOrder> = emptyList(),
    val pendingFailurePopups: List<ScheduledOrderFailurePopup> = emptyList(),
    val restaurantImageById: Map<String, String> = emptyMap(),
    val selectedTab: OrdersTab = OrdersTab.ACTIVE,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val ratingOrderId: String? = null,
    val ratingInitialStars: Int = 5,
    val ratingInitialComment: String = "",
    val isEditingRating: Boolean = false,
    val isSubmittingRating: Boolean = false,
    val upcomingScheduledCount: Int = 0,
    val detailsOrder: Order? = null,
    val detailsScheduledOrder: ScheduledOrder? = null,
    val rescheduleTarget: ScheduledOrder? = null,
    val rescheduleDraftUtcMs: Long = 0L,
    val showRescheduleDatePicker: Boolean = false,
    val showRescheduleTimePicker: Boolean = false,
    val executingScheduleId: String? = null,
    val generatingInvoiceOrderId: String? = null,
    val readyInvoice: InvoiceDocument? = null
) : MviState {

    val activeOrders: List<Order>
        get() = orders.filter { it.orderStatus.isActive }

    val completedOrders: List<Order>
        get() = orders.filter { it.orderStatus == OrderStatus.DELIVERED }

    val cancelledOrders: List<Order>
        get() = orders.filter { it.orderStatus == OrderStatus.CANCELLED }

    val activeFailurePopup: ScheduledOrderFailurePopup?
        get() = pendingFailurePopups.firstOrNull()

    val visibleOrders: List<Order>
        get() = when (selectedTab) {
            OrdersTab.ACTIVE -> activeOrders
            OrdersTab.SCHEDULED -> emptyList()
            OrdersTab.COMPLETED -> completedOrders
            OrdersTab.CANCELLED -> cancelledOrders
        }

    val totalOrderAndScheduleCount: Int
        get() = orders.size + scheduledOrders.count {
            it.status == ScheduledOrderStatus.UPCOMING ||
                it.status == ScheduledOrderStatus.PLACING_ORDER ||
                it.status == ScheduledOrderStatus.PAUSED ||
                it.status == ScheduledOrderStatus.FAILED
        }
}

sealed interface OrdersIntent : MviIntent {
    data object Refresh : OrdersIntent
    data class SelectTab(val tab: OrdersTab) : OrdersIntent
    data class Reorder(val orderId: String) : OrdersIntent
    data class OpenRatingDialog(val orderId: String) : OrdersIntent
    data object DismissRatingDialog : OrdersIntent
    data class SubmitRating(val orderId: String, val rating: Int, val comment: String) : OrdersIntent
    data class OpenOrderDetails(val order: Order) : OrdersIntent
    data object DismissOrderDetails : OrdersIntent
    data class DownloadInvoice(val order: Order) : OrdersIntent
    data object DismissInvoiceReady : OrdersIntent
    // --- Scheduled Orders inside Order History ---
    data class OpenScheduledDetails(val schedule: ScheduledOrder) : OrdersIntent
    data object DismissScheduledDetails : OrdersIntent
    data class CancelScheduledOrder(val scheduleId: String) : OrdersIntent
    data class OpenReschedule(val schedule: ScheduledOrder) : OrdersIntent
    data object DismissReschedule : OrdersIntent
    data object OpenRescheduleDatePicker : OrdersIntent
    data object DismissRescheduleDatePicker : OrdersIntent
    data class ConfirmRescheduleDate(val utcDateMillis: Long) : OrdersIntent
    data object OpenRescheduleTimePicker : OrdersIntent
    data object DismissRescheduleTimePicker : OrdersIntent
    data class ConfirmRescheduleTime(val hourOfDay: Int, val minute: Int) : OrdersIntent
    data object SaveReschedule : OrdersIntent
    data class PlaceScheduledOrderNow(val scheduleId: String) : OrdersIntent
    data class AcknowledgeFailurePopup(val scheduleId: String) : OrdersIntent
    data class HandleFailurePrimaryAction(val popup: ScheduledOrderFailurePopup) : OrdersIntent
    data class HandleFailureCancelOrder(val popup: ScheduledOrderFailurePopup) : OrdersIntent
}

sealed interface OrdersEffect : MviEffect {
    data object NavigateToCart : OrdersEffect
    data class NavigateToRestaurant(val restaurantId: String) : OrdersEffect
    data class NavigateToTrackOrder(val orderId: String) : OrdersEffect
    data class ShowMessage(val message: String) : OrdersEffect
}
