package com.foodflow.feature.tracking

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.usecase.order.CancelOrderUseCase
import com.foodflow.domain.usecase.order.ObserveOrderTimelineUseCase
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import com.foodflow.domain.usecase.tracking.GetDeliveryRouteUseCase
import com.foodflow.domain.usecase.tracking.ObserveActiveTrackedOrderUseCase
import com.foodflow.domain.usecase.tracking.ObserveDeliveryLocationUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingConnectionUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingDiagnosticsUseCase
import com.foodflow.domain.usecase.tracking.ReconcileOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.ReconnectOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StartOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StopOrderTrackingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrderTrackingViewModel @Inject constructor(
    private val observeOrder: ObserveOrderUseCase,
    private val observeActiveTrackedOrder: ObserveActiveTrackedOrderUseCase,
    private val observeOrderTimeline: ObserveOrderTimelineUseCase,
    private val observeDeliveryLocation: ObserveDeliveryLocationUseCase,
    private val observeTrackingConnection: ObserveTrackingConnectionUseCase,
    private val observeTrackingDiagnostics: ObserveTrackingDiagnosticsUseCase,
    private val startOrderTracking: StartOrderTrackingUseCase,
    private val stopOrderTracking: StopOrderTrackingUseCase,
    private val reconnectOrderTracking: ReconnectOrderTrackingUseCase,
    private val reconcileOrderTracking: ReconcileOrderTrackingUseCase,
    private val getDeliveryRoute: GetDeliveryRouteUseCase,
    private val cancelOrderUseCase: CancelOrderUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<OrderTrackingUiState, OrderTrackingIntent, OrderTrackingEffect>(OrderTrackingUiState()) {

    private var activeOrderId: String? = null
    private var observationJob: Job? = null
    private var reconcileJob: Job? = null
    private var loadedRouteKey: String? = null

    /** Handles tracking actions such as starting, stopping, refreshing, or cancelling an order. */
    override fun onIntent(intent: OrderTrackingIntent) {
        when (intent) {
            is OrderTrackingIntent.Start -> start(intent.orderId)
            OrderTrackingIntent.Stop -> {
                // Only detach this screen's local collector; application-level real-time
                // tracking continues uninterrupted across Dashboard and other screens.
                observationJob?.cancel()
                observationJob = null
            }
            OrderTrackingIntent.ReconnectAndReconcile -> reconnectAndReconcile()
            OrderTrackingIntent.CancelOrder -> cancelActiveOrder()
        }
    }

    /** Observes the selected order and its tracking updates, starting tracking when needed. */
    private fun start(orderId: String) {
        if (activeOrderId == orderId && observationJob?.isActive == true) {
            startOrderTracking(orderId)
            return
        }
        activeOrderId = orderId
        loadedRouteKey = null
        observationJob?.cancel()

        val mergedOrderFlow = combine(
            observeOrder(orderId),
            observeActiveTrackedOrder()
        ) { roomOrder, trackedOrder ->
            val matchingTracked = trackedOrder?.takeIf { it.id == orderId }
            when {
                matchingTracked == null -> roomOrder
                roomOrder == null -> matchingTracked
                matchingTracked.lastEventSequence >= roomOrder.lastEventSequence -> matchingTracked
                else -> roomOrder
            }
        }

        val coreFlow = combine(
            mergedOrderFlow,
            observeOrderTimeline(orderId),
            observeDeliveryLocation(orderId)
        ) { order, timeline, location ->
            Triple(order, timeline, location)
        }

        val statusFlow = combine(
            observeTrackingConnection(),
            observeTrackingDiagnostics(),
            connectivityObserver.isOnline
        ) { connState, diagnostics, isOnline ->
            Triple(connState, diagnostics, isOnline)
        }

        observationJob = combine(coreFlow, statusFlow) { (order, timeline, location), (connState, diagnostics, isOnline) ->
            updateState {
                copy(
                    order = order,
                    timeline = timeline,
                    deliveryLocation = location,
                    connectionState = connState,
                    diagnostics = diagnostics,
                    isOffline = !isOnline
                )
            }
            if (order != null) {
                ensureRoadRouteLoaded(
                    origin = RouteCoordinate(order.restaurantLat, order.restaurantLng),
                    destination = RouteCoordinate(order.deliveryLat, order.deliveryLng)
                )
            }
        }.launchIn(viewModelScope)

        startOrderTracking(orderId)
    }

    /** Loads the delivery route once for the current restaurant and destination coordinates. */
    private fun ensureRoadRouteLoaded(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ) {
        val key = "${origin.latitude},${origin.longitude}->${destination.latitude},${destination.longitude}"
        if (loadedRouteKey == key) return
        loadedRouteKey = key
        viewModelScope.launch {
            val route = getDeliveryRoute(origin, destination)
            updateState { copy(deliveryRoute = route) }
        }
    }

    /** Reconnects tracking and fetches missed order updates when the device is online. */
    private fun reconnectAndReconcile() {
        val orderId = activeOrderId ?: return
        if (state.value.isReconciling || reconcileJob?.isActive == true) return
        if (!connectivityObserver.isCurrentlyOnline()) {
            viewModelScope.launch {
                emitEffect(OrderTrackingEffect.ShowMessage("You're offline. Showing last known order status."))
            }
            return
        }
        reconcileJob = viewModelScope.launch {
            updateState { copy(isReconciling = true) }
            try {
                reconnectOrderTracking()
                when (val res = reconcileOrderTracking(orderId)) {
                    is AppResult.Success -> emitEffect(OrderTrackingEffect.ShowMessage("Order status updated."))
                    is AppResult.Error -> emitEffect(OrderTrackingEffect.ShowMessage(res.error.userMessage))
                }
            } catch (_: Throwable) {
                emitEffect(OrderTrackingEffect.ShowMessage("Couldn't refresh tracking status right now."))
            } finally {
                updateState { copy(isReconciling = false) }
            }
        }
    }

    /** Requests cancellation of the currently tracked order and stops tracking on success. */
    private fun cancelActiveOrder() {
        val orderId = activeOrderId ?: return
        viewModelScope.launch {
            updateState { copy(isCancelling = true) }
            when (val res = cancelOrderUseCase(orderId, "Cancelled from live tracking")) {
                is AppResult.Success -> {
                    stopOrderTracking()
                    emitEffect(OrderTrackingEffect.ShowMessage("Order cancelled. Refund initiated if applicable."))
                }
                is AppResult.Error -> emitEffect(OrderTrackingEffect.ShowMessage(res.error.userMessage))
            }
            updateState { copy(isCancelling = false) }
        }
    }

    /** Releases this screen's order observation when its ViewModel is destroyed. */
    override fun onCleared() {
        observationJob?.cancel()
        super.onCleared()
    }
}
