package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.ScheduleValidationResult
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class ValidateScheduledOrderUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Checks a proposed scheduled order and returns any validation issues. */
    suspend operator fun invoke(params: CreateScheduledOrderParams): ScheduleValidationResult =
        scheduledOrderRepository.validateBeforeCreate(params)
}
