package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveScheduledOrderDetailUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Provides live updates for one scheduled order, or null if it is missing. */
    operator fun invoke(scheduleId: String): Flow<ScheduledOrder?> =
        scheduledOrderRepository.observeScheduledOrder(scheduleId)
}
