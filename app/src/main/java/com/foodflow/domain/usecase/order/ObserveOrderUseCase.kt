package com.foodflow.domain.usecase.order

import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Provides live updates for one order, or null if it is not available. */
    operator fun invoke(orderId: String): Flow<Order?> = orderRepository.observeOrder(orderId)
}
