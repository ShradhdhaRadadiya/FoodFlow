package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class CancelScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Cancels the scheduled order and stores the optional cancellation reason. */
    suspend operator fun invoke(scheduleId: String, reason: String = ""): AppResult<ScheduledOrder> =
        scheduledOrderRepository.cancelScheduledOrder(scheduleId = scheduleId, reason = reason)
}
