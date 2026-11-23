package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class UpdateScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Changes the scheduled time and recurrence rule for an existing order. */
    suspend operator fun invoke(
        scheduleId: String,
        newScheduledAtUtcMs: Long,
        newRecurrenceRule: ScheduleRecurrenceRule
    ): AppResult<ScheduledOrder> =
        scheduledOrderRepository.updateScheduledOrder(
            scheduleId = scheduleId,
            newScheduledAtUtcMs = newScheduledAtUtcMs,
            newRecurrenceRule = newRecurrenceRule
        )
}
