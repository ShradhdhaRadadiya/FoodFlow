package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class ReorderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Adds the items from the specified past order to the current cart. */
    suspend operator fun invoke(orderId: String): AppResult<Unit> =
        orderRepository.reorder(orderId)
}
