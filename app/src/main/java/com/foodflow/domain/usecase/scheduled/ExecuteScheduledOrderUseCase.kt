package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class ExecuteScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Runs a due scheduled-order occurrence and reports its outcome. */
    suspend operator fun invoke(
        scheduleId: String,
        expectedOccurrenceNumber: Int? = null,
        forceRunNowForDemo: Boolean = false
    ): AppResult<ScheduleExecutionOutcome> =
        scheduledOrderRepository.executeDueOccurrence(
            scheduleId = scheduleId,
            expectedOccurrenceNumber = expectedOccurrenceNumber,
            forceRunNowForDemo = forceRunNowForDemo
        )
}
