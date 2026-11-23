package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class RefreshOrdersUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Fetches the latest order information and refreshes the local order data. */
    suspend operator fun invoke(): AppResult<Unit> = orderRepository.refreshOrders()
}
