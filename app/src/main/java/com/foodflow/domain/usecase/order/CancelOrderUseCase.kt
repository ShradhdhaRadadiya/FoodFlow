package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class CancelOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Cancels the specified order and records the customer's reason. */
    suspend operator fun invoke(orderId: String, reason: String): AppResult<Order> =
        orderRepository.cancelOrder(orderId, reason)
}
