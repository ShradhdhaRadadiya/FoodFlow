package com.foodflow.domain.usecase.tracking

import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.domain.repository.OrderTrackingRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveTrackingConnectionUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Provides the current connection state for order tracking. */
    operator fun invoke(): StateFlow<WebSocketConnectionState> =
        orderTrackingRepository.connectionState
}
