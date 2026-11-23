package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class SubmitIdempotentOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Submits the checkout attempt safely so retrying cannot create duplicate orders. */
    suspend operator fun invoke(attempt: CheckoutAttempt): AppResult<Order> =
        orderRepository.submitIdempotentOrder(attempt)
}
