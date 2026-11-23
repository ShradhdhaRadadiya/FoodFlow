package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.repository.ScheduledOrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetScheduledOrdersUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Provides live updates to all of the user's scheduled orders. */
    operator fun invoke(): Flow<List<ScheduledOrder>> =
        scheduledOrderRepository.observeScheduledOrders()
}
