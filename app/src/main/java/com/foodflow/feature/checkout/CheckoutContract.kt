package com.foodflow.feature.checkout

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.CheckoutOrderMode
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleRecurrenceType
import com.foodflow.domain.model.SelectedMapPin
import java.util.Calendar
import java.util.TimeZone

data class CheckoutUiState(
    val items: List<CartItem> = emptyList(),
    val restaurant: Restaurant? = null,
    val selectedAddress: DeliveryAddress? = null,
    val selectedPaymentMethod: PaymentMethod = PaymentMethod.UPI,
    val appliedCouponCode: String? = null,
    val breakdown: CartPriceBreakdown = CartPriceBreakdown(),
    val activeAttempt: CheckoutAttempt? = null,
    val isOffline: Boolean = false,
    val isSubmitting: Boolean = false,
    val showQuickMapSheet: Boolean = false,
    val errorMessage: String? = null,
    // --- Scheduled Ordering Configuration ---
    val orderMode: CheckoutOrderMode = CheckoutOrderMode.ORDER_NOW,
    val scheduledAtUtcMs: Long = defaultFutureSlotMs(),
    val recurrenceType: ScheduleRecurrenceType = ScheduleRecurrenceType.ONE_TIME,
    val selectedWeekdays: Set<Int> = setOf(Calendar.FRIDAY),
    val selectedDayOfMonth: Int = 15,
    val endConditionType: ScheduleEndConditionType = ScheduleEndConditionType.NEVER,
    val endDateUtcMs: Long? = null,
    val maxOccurrences: Int = 4,
    val priceConsentPolicy: SchedulePriceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE,
    val operatingHours: RestaurantOperatingHours = RestaurantOperatingHours(),
    val scheduleValidationErrors: List<String> = emptyList(),
    val scheduleSummaryLabel: String = "One-time order placement",
    val scheduleConfirmationSentence: String = "",
    val showDatePicker: Boolean = false,
    val showTimePicker: Boolean = false,
    val showEndDatePicker: Boolean = false
) : MviState {

    /** Builds a schedule rule from the current checkout recurrence settings. */
    fun toRecurrenceRule(): ScheduleRecurrenceRule {
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz).apply { timeInMillis = scheduledAtUtcMs }
        return ScheduleRecurrenceRule(
            type = recurrenceType,
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            weekdays = if (recurrenceType == ScheduleRecurrenceType.WEEKLY) selectedWeekdays.toList() else emptyList(),
            dayOfMonth = if (recurrenceType == ScheduleRecurrenceType.MONTHLY) selectedDayOfMonth else null,
            endConditionType = if (recurrenceType == ScheduleRecurrenceType.ONE_TIME) {
                ScheduleEndConditionType.NEVER
            } else {
                endConditionType
            },
            endDateUtcMs = if (endConditionType == ScheduleEndConditionType.ON_DATE) endDateUtcMs else null,
            maxOccurrences = if (endConditionType == ScheduleEndConditionType.AFTER_OCCURRENCES) maxOccurrences else null,
            timezoneId = tz.id
        )
    }

    companion object {
        /** Chooses the default schedule time a short period after the current time. */
        fun defaultFutureSlotMs(nowUtcMs: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowUtcMs
                add(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour < 9) {
                cal.set(Calendar.HOUR_OF_DAY, 12)
            } else if (hour >= 22) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 12)
            }
            return cal.timeInMillis
        }
    }
}

sealed interface CheckoutIntent : MviIntent {
    data class Initialize(val couponCode: String?) : CheckoutIntent
    data class SelectPaymentMethod(val method: PaymentMethod) : CheckoutIntent
    data object OpenQuickMapSheet : CheckoutIntent
    data object DismissQuickMapSheet : CheckoutIntent
    data class ConfirmAdjustedPin(val pin: SelectedMapPin) : CheckoutIntent
    data object PlaceOrder : CheckoutIntent
    data object DismissError : CheckoutIntent
    // --- Scheduled Ordering Intents ---
    data class SelectOrderMode(val mode: CheckoutOrderMode) : CheckoutIntent
    data class SelectRecurrenceType(val type: ScheduleRecurrenceType) : CheckoutIntent
    data object OpenDatePicker : CheckoutIntent
    data object DismissDatePicker : CheckoutIntent
    data class ConfirmDateSelected(val utcDateMillis: Long) : CheckoutIntent
    data object OpenTimePicker : CheckoutIntent
    data object DismissTimePicker : CheckoutIntent
    data class ConfirmTimeSelected(val hourOfDay: Int, val minute: Int) : CheckoutIntent
    data class ToggleWeekday(val calendarDayOfWeek: Int) : CheckoutIntent
    data class SetDayOfMonth(val day: Int) : CheckoutIntent
    data class SelectEndCondition(val endCondition: ScheduleEndConditionType) : CheckoutIntent
    data object OpenEndDatePicker : CheckoutIntent
    data object DismissEndDatePicker : CheckoutIntent
    data class ConfirmEndDateSelected(val utcDateMillis: Long) : CheckoutIntent
    data class SetMaxOccurrences(val count: Int) : CheckoutIntent
    data class SelectPriceConsentPolicy(val policy: SchedulePriceConsentPolicy) : CheckoutIntent
}

sealed interface CheckoutEffect : MviEffect {
    data class NavigateToPayment(val orderId: String, val idempotencyKey: String) : CheckoutEffect
    data class NavigateToScheduleConfirmation(val scheduleId: String) : CheckoutEffect
    data class ShowMessage(val message: String) : CheckoutEffect
}
