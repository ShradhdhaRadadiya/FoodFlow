package com.foodflow.domain.model

import kotlinx.serialization.Serializable

/**
 * Checkout mode selection allowing customers to order immediately or schedule placement for later.
 */
enum class CheckoutOrderMode(val label: String) {
    ORDER_NOW("Order Now"),
    SCHEDULE_ORDER("Schedule for Later")
}

/**
 * Supported scheduling cadence types for automated order placement.
 */
enum class ScheduleRecurrenceType(val label: String, val subtitle: String) {
    ONE_TIME("One-Time", "Place order once at the chosen date & time"),
    DAILY("Daily", "Place order every day at the selected time"),
    WEEKLY("Weekly", "Place order on selected weekdays"),
    MONTHLY("Monthly", "Place order on a chosen day of each month")
}

/**
 * End condition rules for recurring schedules.
 */
enum class ScheduleEndConditionType(val label: String) {
    NEVER("No end date"),
    ON_DATE("End on date"),
    AFTER_OCCURRENCES("After N orders")
}

/**
 * Lifecycle state of a scheduled order configuration.
 * Progression: UPCOMING ("Scheduled") -> PLACING_ORDER -> COMPLETED ("Order Placed") | FAILED | CANCELLED.
 */
enum class ScheduledOrderStatus(val label: String) {
    UPCOMING("Scheduled"),
    PLACING_ORDER("Placing Order"),
    PAUSED("Paused"),
    COMPLETED("Order Placed"),
    CANCELLED("Cancelled"),
    FAILED("Failed");

    val isActionable: Boolean
        get() = this == UPCOMING || this == PAUSED || this == FAILED

    val canReschedule: Boolean
        get() = this == UPCOMING || this == PAUSED || this == FAILED

    val canCancel: Boolean
        get() = this == UPCOMING || this == PAUSED || this == FAILED
}

/**
 * Execution outcome status for an individual scheduled occurrence.
 */
enum class ScheduleExecutionStatus(val label: String) {
    EXECUTING("Placing Order"),
    SUCCEEDED("Order Placed"),
    FAILED_VALIDATION("Validation Failed"),
    FAILED_PAYMENT("Payment Failed"),
    SKIPPED("Skipped"),
    RETRY_SCHEDULED("Retry Scheduled")
}

/**
 * Structured reason for why a scheduled occurrence did not generate an order.
 */
enum class ScheduleFailureCategory(val label: String) {
    NONE("None"),
    RESTAURANT_CLOSED("Restaurant Closed"),
    RESTAURANT_PAUSED("Restaurant Paused"),
    ITEM_UNAVAILABLE("Items Unavailable"),
    PRICE_CHANGED_REQUIRES_CONSENT("Price Changed"),
    PAYMENT_FAILED("Payment Failed"),
    DELIVERY_UNAVAILABLE("Delivery Unavailable"),
    NETWORK_ERROR("Network Unavailable"),
    SCHEDULE_INACTIVE("Schedule Inactive")
}

/**
 * Explicit customer price consent policy applied at order placement time.
 * Default [STRICT_NO_INCREASE] ensures customers are never silently charged
 * more than the amount they originally authorized.
 */
enum class SchedulePriceConsentPolicy(val label: String) {
    STRICT_NO_INCREASE("Never charge above authorized amount"),
    ALLOW_SMALL_VARIANCE("Allow up to 5% price variance")
}

/**
 * Configurable restaurant operating window and prep lead-time constraints.
 */
data class RestaurantOperatingHours(
    val openHour: Int = 8,
    val openMinute: Int = 0,
    val closeHour: Int = 23,
    val closeMinute: Int = 0,
    val preparationLeadMinutes: Int = 20
) {
    /** Checks whether a local time falls within this restaurant's opening hours. */
    fun isWithinOperatingHours(hourOfDay: Int, minute: Int): Boolean {
        val targetMinutes = hourOfDay * 60 + minute
        val openMinutes = openHour * 60 + openMinute
        val closeMinutes = closeHour * 60 + closeMinute
        return targetMinutes in openMinutes..closeMinutes
    }

    /** Formats the opening and closing times as a readable range. */
    fun formattedWindow(): String =
        "${formatClock(openHour, openMinute)} – ${formatClock(closeHour, closeMinute)}"

    /** Formats an hour and minute as a 12-hour clock label. */
    private fun formatClock(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(java.util.Locale.US, "%d:%02d %s", displayHour, minute, period)
    }
}

/**
 * Timezone-aware recurrence rule stored alongside UTC order-placement timestamps.
 * [weekdays] uses [java.util.Calendar] weekday constants (Calendar.SUNDAY=1 .. Calendar.SATURDAY=7).
 */
@Serializable
data class ScheduleRecurrenceRule(
    val type: ScheduleRecurrenceType = ScheduleRecurrenceType.ONE_TIME,
    val hourOfDay: Int = 19,
    val minute: Int = 0,
    val weekdays: List<Int> = emptyList(),
    val dayOfMonth: Int? = null,
    val endConditionType: ScheduleEndConditionType = ScheduleEndConditionType.NEVER,
    val endDateUtcMs: Long? = null,
    val maxOccurrences: Int? = null,
    val timezoneId: String = java.util.TimeZone.getDefault().id
) {
    val isRecurring: Boolean get() = type != ScheduleRecurrenceType.ONE_TIME
}

/**
 * Complete scheduled order domain entity persisted locally via Room.
 * [nextExecutionAtUtcMs] represents the requested order placement timestamp (when the order
 * will be submitted to the restaurant), NOT the estimated delivery arrival time.
 */
data class ScheduledOrder(
    val id: String,
    val userId: String,
    val restaurantId: String,
    val restaurantName: String,
    val restaurantAddress: String,
    val restaurantLat: Double,
    val restaurantLng: Double,
    val addressId: String,
    val deliveryAddress: String,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val deliveryInstructions: String,
    val items: List<OrderItem>,
    val authorizedSubtotal: Double,
    val authorizedDeliveryFee: Double,
    val authorizedPackagingCharge: Double,
    val authorizedTaxes: Double,
    val authorizedDiscount: Double,
    val authorizedTotalPayable: Double,
    val couponCode: String?,
    val paymentMethod: PaymentMethod,
    val priceConsentPolicy: SchedulePriceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE,
    val recurrenceRule: ScheduleRecurrenceRule,
    val nextExecutionAtUtcMs: Long,
    val status: ScheduledOrderStatus,
    val completedOccurrences: Int = 0,
    val lastExecutedAtUtcMs: Long? = null,
    val lastGeneratedOrderId: String? = null,
    val lastFailureCategory: ScheduleFailureCategory = ScheduleFailureCategory.NONE,
    val lastFailureReason: String? = null,
    val failureAcknowledged: Boolean = false,
    val creationIdempotencyKey: String? = null,
    val retryCount: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Action types offered inside a scheduled order failure popup.
 */
enum class ScheduledFailureAction(val label: String) {
    RESCHEDULE("Reschedule"),
    REVIEW_ORDER("Review Order"),
    CANCEL_ORDER("Cancel Order")
}

/**
 * Structured in-app popup payload presented when a scheduled order cannot be placed
 * at its requested order placement time (e.g., restaurant closed, paused, items unavailable, payment failed).
 */
data class ScheduledOrderFailurePopup(
    val scheduleId: String,
    val restaurantId: String,
    val restaurantName: String,
    val failureCategory: ScheduleFailureCategory,
    val title: String,
    val message: String,
    val unavailableItems: List<String> = emptyList(),
    val primaryAction: ScheduledFailureAction = ScheduledFailureAction.RESCHEDULE,
    val secondaryAction: ScheduledFailureAction = ScheduledFailureAction.CANCEL_ORDER,
    val schedule: ScheduledOrder
)

/**
 * Independent execution record for each scheduled occurrence.
 */
data class ScheduledOrderExecution(
    val id: String,
    val scheduleId: String,
    val occurrenceNumber: Int,
    val idempotencyKey: String,
    val scheduledForUtcMs: Long,
    val executedAtUtcMs: Long,
    val status: ScheduleExecutionStatus,
    val failureCategory: ScheduleFailureCategory,
    val failureReason: String?,
    val generatedOrderId: String?,
    val quotedTotalPayable: Double,
    val authorizedTotalPayable: Double,
    val paymentStatus: PaymentStatus,
    val retryCount: Int
)

/**
 * Pre-creation and pre-execution validation result for a scheduled order.
 */
data class ScheduleValidationResult(
    val isValid: Boolean,
    val restaurantOpen: Boolean = true,
    val restaurantAcceptingOrders: Boolean = true,
    val deliveryAvailable: Boolean = true,
    val estimatedDeliveryMinutes: Int = 25,
    val unavailableItemNames: List<String> = emptyList(),
    val recalculatedBreakdown: CartPriceBreakdown? = null,
    val priceDelta: Double = 0.0,
    val requiresPriceConsent: Boolean = false,
    val failureCategory: ScheduleFailureCategory = ScheduleFailureCategory.NONE,
    val errors: List<String> = emptyList()
)

/**
 * Input payload for creating a new scheduled order from the current cart/checkout.
 */
data class CreateScheduledOrderParams(
    val restaurantId: String,
    val addressId: String,
    val paymentMethod: PaymentMethod,
    val couponCode: String?,
    val scheduledAtUtcMs: Long,
    val recurrenceRule: ScheduleRecurrenceRule,
    val priceConsentPolicy: SchedulePriceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE,
    val creationIdempotencyKey: String? = null
)

/**
 * Result returned when an occurrence execution attempt finishes.
 */
sealed interface ScheduleExecutionOutcome {
    data class Succeeded(
        val generatedOrder: Order,
        val execution: ScheduledOrderExecution
    ) : ScheduleExecutionOutcome

    data class Failed(
        val execution: ScheduledOrderExecution,
        val shouldRetry: Boolean
    ) : ScheduleExecutionOutcome

    data class Skipped(
        val reason: String
    ) : ScheduleExecutionOutcome
}
