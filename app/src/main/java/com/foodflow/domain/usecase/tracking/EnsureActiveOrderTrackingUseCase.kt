package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class EnsureActiveOrderTrackingUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Starts tracking for an active order when it is not already running. */
    operator fun invoke() = orderTrackingRepository.ensureActiveOrderTracking()
}
