package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class AdvanceOrderTrackingStepUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Advances the local order-tracking simulation by one step. */
    operator fun invoke() = orderTrackingRepository.advanceNextStep()
}
