package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class ResumeScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Resumes future occurrences of a paused scheduled order. */
    suspend operator fun invoke(scheduleId: String): AppResult<ScheduledOrder> =
        scheduledOrderRepository.resumeScheduledOrder(scheduleId)
}
