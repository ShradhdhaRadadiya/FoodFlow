package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class StartOrderTrackingUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Begins tracking updates for the specified order. */
    operator fun invoke(orderId: String) = orderTrackingRepository.startTracking(orderId)
}
