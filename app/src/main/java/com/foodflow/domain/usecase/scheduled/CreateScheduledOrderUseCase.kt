package com.foodflow.domain.usecase.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class CreateScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Creates a scheduled order from the provided time, items, and recurrence settings. */
    suspend operator fun invoke(params: CreateScheduledOrderParams): AppResult<ScheduledOrder> =
        scheduledOrderRepository.createScheduledOrder(params)
}
