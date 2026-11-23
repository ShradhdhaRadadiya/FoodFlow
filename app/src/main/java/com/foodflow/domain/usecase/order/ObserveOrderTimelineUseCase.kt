package com.foodflow.domain.usecase.order

import com.foodflow.domain.model.OrderTimelineEvent
import com.foodflow.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveOrderTimelineUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Provides the timeline events for the specified order as they change. */
    operator fun invoke(orderId: String): Flow<List<OrderTimelineEvent>> =
        orderRepository.observeOrderTimeline(orderId)
}
