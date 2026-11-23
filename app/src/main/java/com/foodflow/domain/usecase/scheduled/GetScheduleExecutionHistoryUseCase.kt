package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.ScheduledOrderExecution
import com.foodflow.domain.repository.ScheduledOrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetScheduleExecutionHistoryUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Provides live execution history for the specified scheduled order. */
    operator fun invoke(scheduleId: String): Flow<List<ScheduledOrderExecution>> =
        scheduledOrderRepository.observeExecutionHistory(scheduleId)
}
