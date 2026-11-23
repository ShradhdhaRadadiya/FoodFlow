package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class ResumeActiveOrderTrackingUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Restarts tracking updates for the active order after the app resumes. */
    operator fun invoke() = orderTrackingRepository.resumeActiveOrderTracking()
}
