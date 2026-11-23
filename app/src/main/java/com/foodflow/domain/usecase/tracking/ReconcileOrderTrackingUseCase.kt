package com.foodflow.domain.usecase.tracking

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class ReconcileOrderTrackingUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Fetches the latest order state to catch up on missed tracking updates. */
    suspend operator fun invoke(orderId: String): AppResult<Order> =
        orderTrackingRepository.reconcileOrder(orderId)
}
