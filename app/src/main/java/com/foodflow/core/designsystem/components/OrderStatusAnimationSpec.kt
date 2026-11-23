package com.foodflow.core.designsystem.components

import androidx.annotation.RawRes
import com.airbnb.lottie.compose.LottieConstants
import com.example.R
import com.foodflow.domain.model.OrderStatus

data class OrderStatusAnimationSpec(
    @RawRes val rawRes: Int,
    val iterations: Int,
    val speed: Float,
    val statusHint: String,
    val contentDescription: String
)

/** Selects the animation asset and playback settings that match the current order status. */
fun resolveOrderStatusAnimation(status: OrderStatus): OrderStatusAnimationSpec = when (status) {
    OrderStatus.PLACED -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_placed,
        iterations = LottieConstants.IterateForever,
        speed = 1.0f,
        statusHint = "Confirming your order with the kitchen",
        contentDescription = "Order placed animation"
    )
    OrderStatus.ACCEPTED -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_placed,
        iterations = LottieConstants.IterateForever,
        speed = 1.15f,
        statusHint = "Kitchen accepted your order and queued preparation",
        contentDescription = "Order accepted animation"
    )
    OrderStatus.PREPARING -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_preparing,
        iterations = LottieConstants.IterateForever,
        speed = 1.0f,
        statusHint = "Chefs are freshly cooking your dishes",
        contentDescription = "Food preparing animation"
    )
    OrderStatus.READY_FOR_PICKUP -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_preparing,
        iterations = LottieConstants.IterateForever,
        speed = 0.85f,
        statusHint = "Sealed fresh and waiting for delivery partner pickup",
        contentDescription = "Order ready for pickup animation"
    )
    OrderStatus.PARTNER_ASSIGNED -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_on_the_way,
        iterations = LottieConstants.IterateForever,
        speed = 0.9f,
        statusHint = "Delivery partner is heading to the restaurant",
        contentDescription = "Delivery partner assigned animation"
    )
    OrderStatus.PICKED_UP -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_on_the_way,
        iterations = LottieConstants.IterateForever,
        speed = 1.2f,
        statusHint = "On the way to your doorstep right now",
        contentDescription = "Order out for delivery animation"
    )
    OrderStatus.DELIVERED -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_delivered,
        iterations = 1,
        speed = 1.0f,
        statusHint = "Delivered safely — enjoy your meal!",
        contentDescription = "Order delivered celebration animation"
    )
    OrderStatus.CANCELLED -> OrderStatusAnimationSpec(
        rawRes = R.raw.lottie_order_cancelled,
        iterations = 1,
        speed = 1.0f,
        statusHint = "This order has been cancelled",
        contentDescription = "Order cancelled animation"
    )
}
