package com.foodflow.feature.tracking

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.domain.model.DeliveryRoute
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderTimelineEvent
import com.foodflow.domain.model.RiderLocation
import com.foodflow.domain.model.TrackingDiagnosticInfo

data class OrderTrackingUiState(
    val order: Order? = null,
    val timeline: List<OrderTimelineEvent> = emptyList(),
    val deliveryLocation: RiderLocation? = null,
    val deliveryRoute: DeliveryRoute? = null,
    val connectionState: WebSocketConnectionState = WebSocketConnectionState.DISCONNECTED,
    val diagnostics: TrackingDiagnosticInfo = TrackingDiagnosticInfo(),
    val isOffline: Boolean = false,
    val isReconciling: Boolean = false,
    val isCancelling: Boolean = false
) : MviState

sealed interface OrderTrackingIntent : MviIntent {
    data class Start(val orderId: String) : OrderTrackingIntent
    data object Stop : OrderTrackingIntent
    data object ReconnectAndReconcile : OrderTrackingIntent
    data object CancelOrder : OrderTrackingIntent
}

sealed interface OrderTrackingEffect : MviEffect {
    data class ShowMessage(val message: String) : OrderTrackingEffect
}
