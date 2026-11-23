package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class ReconnectOrderTrackingUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Requests a new connection for order tracking. */
    operator fun invoke() = orderTrackingRepository.reconnect()
}
