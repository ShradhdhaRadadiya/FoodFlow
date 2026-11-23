package com.foodflow.core.designsystem.components

import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.OrderTimelineEvent

/**
 * Pure presentation stage enum mapped strictly from FoodFlow's existing [OrderStatus]
 * and real-time rider progress. Guarantees delivery visuals never play before PICKED_UP
 * and delivered celebrations never play before DELIVERED.
 */
enum class OrderJourneyStage(
    val stepIndex: Int,
    val badgeLabel: String,
    val title: String,
    val subtitle: String
) {
    CONFIRMED(
        stepIndex = 0,
        badgeLabel = "Stage 1 of 5",
        title = "Order Confirmed",
        subtitle = "The kitchen has received your order and queued it up."
    ),
    PREPARING(
        stepIndex = 1,
        badgeLabel = "Stage 2 of 5",
        title = "Preparing Your Meal",
        subtitle = "Chefs are cooking your dishes fresh right now."
    ),
    READY_FOR_PICKUP(
        stepIndex = 2,
        badgeLabel = "Stage 3 of 5",
        title = "Packed & Ready",
        subtitle = "Your food is sealed and waiting at the pickup counter."
    ),
    PARTNER_ASSIGNED(
        stepIndex = 2,
        badgeLabel = "Stage 3 of 5",
        title = "Delivery Partner Arriving",
        subtitle = "Your delivery partner is heading to the restaurant to collect your order."
    ),
    PICKED_UP(
        stepIndex = 3,
        badgeLabel = "Stage 4 of 5",
        title = "Picked Up from Kitchen",
        subtitle = "Your delivery partner has collected your fresh order and is starting the trip."
    ),
    ON_THE_WAY(
        stepIndex = 3,
        badgeLabel = "Stage 4 of 5",
        title = "On the Way to You",
        subtitle = "Your meal is cruising toward your doorstep."
    ),
    DELIVERED(
        stepIndex = 4,
        badgeLabel = "Completed",
        title = "Delivered & Received",
        subtitle = "Your order has arrived. Enjoy your meal!"
    ),
    CANCELLED(
        stepIndex = -1,
        badgeLabel = "Cancelled",
        title = "Order Cancelled",
        subtitle = "This order was cancelled before delivery."
    )
}

data class OrderJourneyMilestoneTiming(
    val stepIndex: Int,
    val label: String,
    val timestampMs: Long?,
    val isEstimated: Boolean = false
)

/** Maps persisted order status and rider progress to the presentation milestone shown to the customer. */
fun resolveOrderJourneyStage(
    status: OrderStatus,
    riderProgress: Float
): OrderJourneyStage = when (status) {
    OrderStatus.PLACED, OrderStatus.ACCEPTED -> OrderJourneyStage.CONFIRMED
    OrderStatus.PREPARING -> OrderJourneyStage.PREPARING
    OrderStatus.READY_FOR_PICKUP -> OrderJourneyStage.READY_FOR_PICKUP
    OrderStatus.PARTNER_ASSIGNED -> OrderJourneyStage.PARTNER_ASSIGNED
    OrderStatus.PICKED_UP -> {
        if (riderProgress >= 0.25f) OrderJourneyStage.ON_THE_WAY else OrderJourneyStage.PICKED_UP
    }
    OrderStatus.DELIVERED -> OrderJourneyStage.DELIVERED
    OrderStatus.CANCELLED -> OrderJourneyStage.CANCELLED
}

/**
 * Matches timeline events by type first and title keywords second, then supplies confirmed/terminal
 * timestamp fallbacks and an ETA-derived delivery time while clearly flagging estimated delivery.
 */
fun resolveJourneyMilestoneTimings(
    order: Order,
    timelineEvents: List<OrderTimelineEvent>
): List<OrderJourneyMilestoneTiming> {
    val stage = resolveOrderJourneyStage(order.orderStatus, order.riderProgress)
    val sortedEvents = timelineEvents.sortedBy { it.timestampMs }

    /** Finds the earliest matching event by canonical type, falling back to a title keyword. */
    fun findEventTime(
        eventTypes: Set<String>,
        titleKeywords: List<String>
    ): Long? {
        sortedEvents.firstOrNull { event ->
            event.eventType.uppercase() in eventTypes
        }?.let { return it.timestampMs }

        return sortedEvents.firstOrNull { event ->
            titleKeywords.any { keyword -> event.title.contains(keyword, ignoreCase = true) }
        }?.timestampMs
    }

    val confirmedTime = findEventTime(
        eventTypes = setOf("ORDER_ACCEPTED", "ORDER_PLACED", "PAYMENT_CONFIRMED"),
        titleKeywords = listOf("accepted", "placed", "confirmed")
    ) ?: order.createdAtMs

    val cookingTime = findEventTime(
        eventTypes = setOf("FOOD_PREPARING", "PREPARING"),
        titleKeywords = listOf("prepar", "cook")
    )

    val packedTime = findEventTime(
        eventTypes = setOf("FOOD_READY", "READY_FOR_PICKUP"),
        titleKeywords = listOf("pack", "ready")
    )

    val onWayTime = findEventTime(
        eventTypes = setOf("ORDER_PICKED_UP", "OUT_FOR_DELIVERY"),
        titleKeywords = listOf("picked up", "on the way", "out for delivery")
    ) ?: findEventTime(
        eventTypes = setOf("DELIVERY_PARTNER_ASSIGNED", "PARTNER_ASSIGNED"),
        titleKeywords = listOf("partner assigned")
    )

    val deliveredActualTime = findEventTime(
        eventTypes = setOf("ORDER_DELIVERED", "DELIVERED"),
        titleKeywords = listOf("delivered")
    ) ?: if (order.orderStatus == OrderStatus.DELIVERED) order.updatedAtMs else null

    val estimatedDeliveryTime = if (deliveredActualTime == null && order.orderStatus.isActive && order.estimatedDeliveryMinutes > 0) {
        val referenceMs = maxOf(order.updatedAtMs, order.createdAtMs)
        referenceMs + order.estimatedDeliveryMinutes * 60_000L
    } else {
        null
    }

    return listOf(
        OrderJourneyMilestoneTiming(
            stepIndex = 0,
            label = "Confirmed",
            timestampMs = if (stage.stepIndex >= 0) confirmedTime else null
        ),
        OrderJourneyMilestoneTiming(
            stepIndex = 1,
            label = "Cooking",
            timestampMs = if (stage.stepIndex >= 1) cookingTime else null
        ),
        OrderJourneyMilestoneTiming(
            stepIndex = 2,
            label = "Packed",
            timestampMs = if (stage.stepIndex >= 2) packedTime else null
        ),
        OrderJourneyMilestoneTiming(
            stepIndex = 3,
            label = "On Way",
            timestampMs = if (stage.stepIndex >= 3) onWayTime else null
        ),
        OrderJourneyMilestoneTiming(
            stepIndex = 4,
            label = "Delivered",
            timestampMs = deliveredActualTime ?: estimatedDeliveryTime,
            isEstimated = deliveredActualTime == null && estimatedDeliveryTime != null
        )
    )
}
