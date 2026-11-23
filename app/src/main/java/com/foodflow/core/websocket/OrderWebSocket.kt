package com.foodflow.core.websocket

import com.foodflow.core.common.DispatcherProvider
import com.foodflow.domain.model.OrderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class OrderEventType(val mappedStatus: OrderStatus?, val title: String) {
    ORDER_ACCEPTED(OrderStatus.ACCEPTED, "Restaurant accepted your order"),
    FOOD_PREPARING(OrderStatus.PREPARING, "Chef started preparing your meal"),
    FOOD_READY(OrderStatus.READY_FOR_PICKUP, "Food is freshly packed & ready"),
    DELIVERY_PARTNER_ASSIGNED(OrderStatus.PARTNER_ASSIGNED, "Delivery partner assigned"),
    ORDER_PICKED_UP(OrderStatus.PICKED_UP, "Order picked up & on the way"),
    RIDER_LOCATION_UPDATED(null, "Rider location updated"),
    ORDER_DELIVERED(OrderStatus.DELIVERED, "Order delivered! Enjoy your meal"),
    ORDER_CANCELLED(OrderStatus.CANCELLED, "Order cancelled"),
    ORDER_DELAYED(null, "Order running slightly behind schedule")
}

enum class WebSocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Immutable real-time order tracking event emitted over the WebSocket abstraction.
 * [sequenceNumber] allows deterministic deduplication and out-of-order rejection.
 */
data class OrderEvent(
    val eventId: String,
    val orderId: String,
    val sequenceNumber: Long,
    val type: OrderEventType,
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val progressFraction: Float? = null,
    val etaMinutes: Int? = null,
    val partnerId: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * OkHttp-aligned WebSocket abstraction so a real `okhttp3.WebSocket` client can
 * replace [LocalSimulatedOrderEventSource] without changing repository or UI layers.
 */
interface OrderEventSource {
    val connectionState: StateFlow<WebSocketConnectionState>
    val events: Flow<OrderEvent>

    /** Opens event delivery for an order, resuming from its status and last applied sequence. */
    fun connect(orderId: String, startFromStatus: OrderStatus, lastSequence: Long, restLat: Double, restLng: Double, destLat: Double, destLng: Double)
    /** Stops the current event stream and clears its active order. */
    fun disconnect()
    /** Reconnects to the active order, resuming from the next un-emitted event. */
    fun reconnect()
    /** Simulates a forced transport loss for recovery-path checks. */
    fun forceDisconnectForTest()
    /** Emits one next scripted event immediately when the simulated source is connected. */
    fun emitManualNextStep()
    /** Replays the previous event to exercise repository deduplication. */
    fun emitDuplicateLastEvent()
    /** Emits an older sequence number to exercise stale-event rejection. */
    fun emitStaleOutOfOrderEvent()
    /** Sets the simulator's delay between automatic events, with a minimum safe interval. */
    fun setEventIntervalMs(intervalMs: Long)
}

/**
 * Deterministic local WebSocket simulator.
 *
 * Production vs Local Simulation note:
 * In production, an OkHttp WebSocket connects to `wss://api.foodflow.in/ws/orders/{id}`
 * with JWT auth, ping/pong heartbeats, and server-side sequence numbers.
 * Locally, this simulator replays realistic restaurant + rider progression events
 * on a controlled coroutine scope, supports manual disconnect/reconnect testing,
 * and allows injecting duplicate and stale events for assessment verification.
 */
@Singleton
class LocalSimulatedOrderEventSource @Inject constructor(
    private val dispatchers: DispatcherProvider
) : OrderEventSource {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val _connectionState = MutableStateFlow(WebSocketConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<OrderEvent>(extraBufferCapacity = 32)
    override val events: Flow<OrderEvent> = _events.asSharedFlow()

    private val connectionLock = Any()
    private var simulationJob: Job? = null
    private var currentOrderId: String? = null
    private var currentSeq: Long = 0L
    private var currentStepIndex: Int = 0
    private var restLat = 23.0225
    private var restLng = 72.5714
    private var destLat = 23.0469
    private var destLng = 72.5310
    private var lastEmittedEvent: OrderEvent? = null
    private var eventIntervalMs: Long = 4_500L
    private var isForcedDisconnected = false
    private var reconnectAttemptCount = 0

    internal var connectInvocationCount: Int = 0
        private set

    private val fullScript: List<OrderEventType> = listOf(
        OrderEventType.ORDER_ACCEPTED,
        OrderEventType.FOOD_PREPARING,
        OrderEventType.FOOD_READY,
        OrderEventType.DELIVERY_PARTNER_ASSIGNED,
        OrderEventType.ORDER_PICKED_UP,
        OrderEventType.RIDER_LOCATION_UPDATED,
        OrderEventType.RIDER_LOCATION_UPDATED,
        OrderEventType.RIDER_LOCATION_UPDATED,
        OrderEventType.ORDER_DELIVERED
    )

    /** Resets simulation state from the supplied order snapshot, then emits remaining events on a coroutine. */
    override fun connect(
        orderId: String,
        startFromStatus: OrderStatus,
        lastSequence: Long,
        restLat: Double,
        restLng: Double,
        destLat: Double,
        destLng: Double
    ) {
        synchronized(connectionLock) {
            val activeState = _connectionState.value == WebSocketConnectionState.CONNECTED ||
                _connectionState.value == WebSocketConnectionState.CONNECTING ||
                _connectionState.value == WebSocketConnectionState.RECONNECTING
            if (currentOrderId == orderId && activeState && simulationJob?.isActive == true) {
                return
            }
            simulationJob?.cancel()
            this.currentOrderId = orderId
            this.currentSeq = lastSequence
            this.restLat = restLat
            this.restLng = restLng
            this.destLat = destLat
            this.destLng = destLng
            this.isForcedDisconnected = false
            this.reconnectAttemptCount = 0
            this.currentStepIndex = mapStatusToNextScriptIndex(startFromStatus)

            if (startFromStatus.isTerminal) {
                _connectionState.value = WebSocketConnectionState.DISCONNECTED
                return
            }

            connectInvocationCount += 1
            _connectionState.value = WebSocketConnectionState.CONNECTING
            simulationJob = scope.launch {
                delay(350L)
                if (!isActive || isForcedDisconnected) return@launch
                _connectionState.value = WebSocketConnectionState.CONNECTED

                while (isActive && currentStepIndex < fullScript.size && !isForcedDisconnected) {
                    delay(eventIntervalMs)
                    if (!isActive || isForcedDisconnected) break
                    emitStepAt(currentStepIndex)
                    currentStepIndex++
                }
            }
        }
    }

    /** Cancels the simulation job and returns connection state to disconnected. */
    override fun disconnect() {
        synchronized(connectionLock) {
            simulationJob?.cancel()
            simulationJob = null
            currentOrderId = null
            reconnectAttemptCount = 0
            _connectionState.value = WebSocketConnectionState.DISCONNECTED
        }
    }

    /** Stops simulation and blocks automatic continuation until reconnect is requested. */
    override fun forceDisconnectForTest() {
        synchronized(connectionLock) {
            isForcedDisconnected = true
            simulationJob?.cancel()
            simulationJob = null
            _connectionState.value = WebSocketConnectionState.DISCONNECTED
        }
    }

    /** Resumes the scripted stream after bounded exponential backoff without replaying applied steps. */
    override fun reconnect() {
        synchronized(connectionLock) {
            currentOrderId ?: return
            if (currentStepIndex >= fullScript.size) {
                _connectionState.value = WebSocketConnectionState.DISCONNECTED
                return
            }
            if ((_connectionState.value == WebSocketConnectionState.CONNECTED ||
                    _connectionState.value == WebSocketConnectionState.RECONNECTING) &&
                simulationJob?.isActive == true
            ) {
                return
            }
            isForcedDisconnected = false
            simulationJob?.cancel()
            reconnectAttemptCount = (reconnectAttemptCount + 1).coerceAtMost(MAX_RECONNECT_ATTEMPTS)
            val backoffDelayMs = (BASE_RECONNECT_BACKOFF_MS * (1L shl (reconnectAttemptCount - 1)))
                .coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
            _connectionState.value = WebSocketConnectionState.RECONNECTING
            simulationJob = scope.launch {
                delay(backoffDelayMs)
                if (!isActive || isForcedDisconnected) return@launch
                reconnectAttemptCount = 0
                _connectionState.value = WebSocketConnectionState.CONNECTED
                while (isActive && currentStepIndex < fullScript.size && !isForcedDisconnected) {
                    delay(eventIntervalMs)
                    if (!isActive || isForcedDisconnected) break
                    emitStepAt(currentStepIndex)
                    currentStepIndex++
                }
            }
        }
    }

    companion object {
        private const val BASE_RECONNECT_BACKOFF_MS = 500L
        private const val MAX_RECONNECT_BACKOFF_MS = 8_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    /** Advances exactly one script entry for deterministic tracking demos. */
    override fun emitManualNextStep() {
        if (_connectionState.value != WebSocketConnectionState.CONNECTED) return
        if (currentStepIndex < fullScript.size) {
            scope.launch {
                emitStepAt(currentStepIndex)
                currentStepIndex++
            }
        }
    }

    /** Re-emits the last event unchanged except for its delivery timestamp. */
    override fun emitDuplicateLastEvent() {
        val last = lastEmittedEvent ?: return
        scope.launch {
            _events.emit(last.copy(timestampMs = System.currentTimeMillis()))
        }
    }

    /** Publishes a lower sequence event to verify that consumers ignore out-of-order data. */
    override fun emitStaleOutOfOrderEvent() {
        val orderId = currentOrderId ?: return
        val staleSeq = (currentSeq - 2).coerceAtLeast(1L)
        scope.launch {
            _events.emit(
                OrderEvent(
                    eventId = "evt_stale_${System.currentTimeMillis()}",
                    orderId = orderId,
                    sequenceNumber = staleSeq,
                    type = OrderEventType.ORDER_ACCEPTED,
                    message = "Stale out-of-order event (seq #$staleSeq) — should be ignored",
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** Updates the auto-progression delay while enforcing the simulator's minimum interval. */
    override fun setEventIntervalMs(intervalMs: Long) {
        eventIntervalMs = intervalMs.coerceAtLeast(100L)
    }

    /** Builds the indexed status/location event, increments its sequence, and publishes it. */
    private suspend fun emitStepAt(index: Int) {
        val orderId = currentOrderId ?: return
        val type = fullScript.getOrNull(index) ?: return
        currentSeq += 1L

        val riderProgress = when (index) {
            4 -> 0.15f
            5 -> 0.42f
            6 -> 0.68f
            7 -> 0.90f
            8 -> 1.0f
            else -> 0.0f
        }
        val lat = restLat + (destLat - restLat) * riderProgress
        val lng = restLng + (destLng - restLng) * riderProgress
        val eta = ((1f - riderProgress) * 22f).toInt().coerceAtLeast(if (type == OrderEventType.ORDER_DELIVERED) 0 else 2)

        val message = when (type) {
            OrderEventType.ORDER_ACCEPTED -> "Kitchen confirmed your order and fired up the wok."
            OrderEventType.FOOD_PREPARING -> "Fresh ingredients are being cooked right now."
            OrderEventType.FOOD_READY -> "Tamper-proof sealed & awaiting delivery partner pickup."
            OrderEventType.DELIVERY_PARTNER_ASSIGNED -> "Rajesh Patel is heading to the restaurant."
            OrderEventType.ORDER_PICKED_UP -> "Rajesh picked up your order and is en route!"
            OrderEventType.RIDER_LOCATION_UPDATED -> "Rider is ${(100 - (riderProgress * 100).toInt()).coerceAtLeast(5)}% away · ETA $eta mins"
            OrderEventType.ORDER_DELIVERED -> "Delivered safely at your doorstep. Bon appétit!"
            OrderEventType.ORDER_CANCELLED -> "Order was cancelled."
            OrderEventType.ORDER_DELAYED -> "Heavy traffic near pickup — ETA updated."
        }

        val event = OrderEvent(
            eventId = "evt_${orderId}_$currentSeq",
            orderId = orderId,
            sequenceNumber = currentSeq,
            type = type,
            message = message,
            latitude = if (index >= 4) lat else restLat,
            longitude = if (index >= 4) lng else restLng,
            progressFraction = riderProgress,
            etaMinutes = eta,
            partnerId = if (index >= 3) "partner_1" else null
        )
        lastEmittedEvent = event
        _events.emit(event)
    }

    /** Finds the first simulated event not already represented by the persisted order status. */
    private fun mapStatusToNextScriptIndex(status: OrderStatus): Int = when (status) {
        OrderStatus.PLACED -> 0
        OrderStatus.ACCEPTED -> 1
        OrderStatus.PREPARING -> 2
        OrderStatus.READY_FOR_PICKUP -> 3
        OrderStatus.PARTNER_ASSIGNED -> 4
        OrderStatus.PICKED_UP -> 5
        OrderStatus.DELIVERED, OrderStatus.CANCELLED -> fullScript.size
    }
}
