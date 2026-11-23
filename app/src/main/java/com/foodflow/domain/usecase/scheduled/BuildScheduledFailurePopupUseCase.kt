package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.ScheduleFailureCategory
import com.foodflow.domain.model.ScheduledFailureAction
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderFailurePopup
import javax.inject.Inject

/**
 * Translates a failed [ScheduledOrder] into a structured, user-friendly in-app popup model
 * covering restaurant closed, restaurant paused, unavailable items, price changes, and payment failures.
 */
class BuildScheduledFailurePopupUseCase @Inject constructor() {

    /**
     * Converts the persisted failure category into UI-ready title, explanation, affected items,
     * and allowed primary/secondary actions. Category-specific messages prefer a stored server or
     * execution reason when present, while the attached schedule keeps its retry/review context.
     */
    operator fun invoke(schedule: ScheduledOrder): ScheduledOrderFailurePopup {
        val unavailableItems = extractUnavailableItems(schedule.lastFailureReason)
        return when (schedule.lastFailureCategory) {
            ScheduleFailureCategory.RESTAURANT_CLOSED -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Unable to Place Your Scheduled Order",
                message = "Unfortunately, the restaurant is not accepting orders at your scheduled time. Please select another time or restaurant.",
                primaryAction = ScheduledFailureAction.RESCHEDULE,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )

            ScheduleFailureCategory.RESTAURANT_PAUSED -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Unable to Place Your Scheduled Order",
                message = "${schedule.restaurantName} has temporarily paused incoming orders at your scheduled time. Please select another time or cancel this scheduled order.",
                primaryAction = ScheduledFailureAction.RESCHEDULE,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )

            ScheduleFailureCategory.ITEM_UNAVAILABLE -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Items Unavailable",
                message = "Some items in your scheduled order are no longer available. Please review your cart and update your order.",
                unavailableItems = unavailableItems,
                primaryAction = ScheduledFailureAction.REVIEW_ORDER,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )

            ScheduleFailureCategory.PRICE_CHANGED_REQUIRES_CONSENT -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Price Updated Since Scheduling",
                message = schedule.lastFailureReason
                    ?: "Menu prices changed beyond your authorized limit, so your scheduled order was not placed.",
                primaryAction = ScheduledFailureAction.REVIEW_ORDER,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )

            ScheduleFailureCategory.PAYMENT_FAILED -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Payment Could Not Be Completed",
                message = schedule.lastFailureReason
                    ?: "We could not authorize payment at your scheduled order placement time. Please reschedule or cancel your order.",
                primaryAction = ScheduledFailureAction.RESCHEDULE,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )

            else -> ScheduledOrderFailurePopup(
                scheduleId = schedule.id,
                restaurantId = schedule.restaurantId,
                restaurantName = schedule.restaurantName,
                failureCategory = schedule.lastFailureCategory,
                title = "Unable to Place Your Scheduled Order",
                message = schedule.lastFailureReason
                    ?: "We could not place your scheduled order with ${schedule.restaurantName}. Please select another time or cancel the order.",
                primaryAction = ScheduledFailureAction.RESCHEDULE,
                secondaryAction = ScheduledFailureAction.CANCEL_ORDER,
                schedule = schedule
            )
        }
    }

    /**
     * Parses the comma-separated item list after the known failure marker; unrelated or malformed
     * failure text yields an empty list so the popup can still show its general explanation.
     */
    private fun extractUnavailableItems(reason: String?): List<String> {
        if (reason.isNullOrBlank()) return emptyList()
        val marker = "Unavailable item(s):"
        val startIdx = reason.indexOf(marker, ignoreCase = true)
        if (startIdx < 0) return emptyList()
        val afterMarker = reason.substring(startIdx + marker.length).trim()
        val beforeSentenceEnd = afterMarker.substringBefore(". Order was not placed").trim()
        return beforeSentenceEnd
            .split(",")
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
    }
}
