package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class RateOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Saves the user's rating and comment for the specified order. */
    suspend operator fun invoke(orderId: String, rating: Int, comment: String): AppResult<Unit> =
        orderRepository.rateOrder(orderId, rating, comment)
}
