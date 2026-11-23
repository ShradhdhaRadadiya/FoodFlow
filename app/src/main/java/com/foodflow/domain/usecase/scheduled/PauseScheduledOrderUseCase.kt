package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class PauseScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Pauses future occurrences of the specified scheduled order. */
    suspend operator fun invoke(scheduleId: String): AppResult<ScheduledOrder> =
        scheduledOrderRepository.pauseScheduledOrder(scheduleId)
}
