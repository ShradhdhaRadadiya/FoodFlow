package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.model.Order
import com.foodflow.domain.repository.OrderTrackingRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveActiveTrackedOrderUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Provides the active tracked order as it changes. */
    operator fun invoke(): StateFlow<Order?> = orderTrackingRepository.activeTrackedOrder
}
