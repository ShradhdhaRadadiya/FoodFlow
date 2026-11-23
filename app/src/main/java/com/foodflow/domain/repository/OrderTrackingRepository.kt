package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.TrackingDiagnosticInfo
import kotlinx.coroutines.flow.StateFlow

interface OrderTrackingRepository {
    val diagnostics: StateFlow<TrackingDiagnosticInfo>
    val connectionState: StateFlow<WebSocketConnectionState>
    val activeTrackedOrder: StateFlow<Order?>
    val activeTrackedOrderId: StateFlow<String?>

    /** Starts tracking the active order if tracking is not already running. */
    fun ensureActiveOrderTracking()
    /** Resumes updates for an active order after tracking was paused. */
    fun resumeActiveOrderTracking()
    /** Records that the delivered-order banner was dismissed. */
    fun dismissDeliveredOrderBanner()
    /** Starts receiving updates for the specified order. */
    fun startTracking(orderId: String)
    /** Stops the current order-tracking session. */
    fun stopTracking()
    /** Requests a new connection to the tracking event source. */
    fun reconnect()
    /** Advances the local order-tracking simulation by one step. */
    fun advanceNextStep()
    /** Fetches the latest order data to recover updates missed while disconnected. */
    suspend fun reconcileOrder(orderId: String): AppResult<Order>
}
