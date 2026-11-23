package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

/**
 * Marks a scheduled order failure popup as acknowledged so it does not repeatedly reopen,
 * while preserving the persisted FAILED state and reason in Order History.
 */
class AcknowledgeScheduledFailureUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Marks the failure alert as seen while keeping the failed schedule in order history. */
    suspend operator fun invoke(scheduleId: String): AppResult<Unit> =
        scheduledOrderRepository.acknowledgeScheduledOrderFailure(scheduleId)
}
