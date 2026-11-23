package com.foodflow.domain.usecase.order

import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveOrdersUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Provides live updates for the user's order history. */
    operator fun invoke(): Flow<List<Order>> = orderRepository.observeOrders()
}
