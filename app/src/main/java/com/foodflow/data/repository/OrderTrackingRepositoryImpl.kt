package com.foodflow.data.repository

import android.util.Log
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.connectivity.ConnectivityStatus
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.core.websocket.OrderEvent
import com.foodflow.core.websocket.OrderEventSource
import com.foodflow.core.websocket.OrderEventType
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.TrackingDiagnosticInfo
import com.foodflow.domain.repository.OrderTrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped single source of truth for real-time order tracking.
 *
 * Manages a single shared WebSocket stream independently of individual screen or ViewModel
 * lifecycles, persists every validated event atomically into Room, publishes synchronized
 * updates to Dashboard, Track Order, and Orders screens, handles network interruptions and
 * app resume, and cleanly terminates the WebSocket only after terminal order states
 * (DELIVERED / CANCELLED) are fully processed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OrderTrackingRepositoryImpl @Inject constructor(
    private val eventSource: OrderEventSource,
    private val orderDao: OrderDao,
    private val catalogDao: CatalogDao,
    private val orderRemote: OrderRemoteDataSource,
    private val scenarioController: MockScenarioController,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) : OrderTrackingRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val lifecycleLock = Any()
    private val eventMutex = Mutex()

    private var collectorJob: Job? = null
    private var activeOrderWatcherJob: Job? = null
    private var connectivityWatcherJob: Job? = null
    private var activeOrderId: String? = null
    private var dismissedDeliveredOrderId: String? = null

    internal var activeCollectorLaunches: Int = 0
        private set

    private val _diagnostics = MutableStateFlow(TrackingDiagnosticInfo())
    override val diagnostics: StateFlow<TrackingDiagnosticInfo> = _diagnostics.asStateFlow()

    override val connectionState: StateFlow<WebSocketConnectionState> = eventSource.connectionState

    private val _activeTrackedOrder = MutableStateFlow<Order?>(null)
    override val activeTrackedOrder: StateFlow<Order?> = _activeTrackedOrder.asStateFlow()

    private val _activeTrackedOrderId = MutableStateFlow<String?>(null)
    override val activeTrackedOrderId: StateFlow<String?> = _activeTrackedOrderId.asStateFlow()

    /** Watches saved orders and automatically tracks the active order. */
    override fun ensureActiveOrderTracking() {
        synchronized(lifecycleLock) {
            if (activeOrderWatcherJob?.isActive == true) return

            activeOrderWatcherJob = scope.launch {
                preferencesDataStore.preferences
                    .map { it.userId ?: DatabaseSeeder.DEFAULT_USER_ID }
                    .distinctUntilChanged()
                    .flatMapLatest { userId ->
                        combine(
                            orderDao.observeOrders(userId),
                            catalogDao.observeDeliveryPartners()
                        ) { orderEntities, partnerEntities ->
                            val partnerMap = partnerEntities.associateBy { it.id }
                            orderEntities.map { entity ->
                                val partner = entity.deliveryPartnerId?.let { partnerMap[it]?.toDomain() }
                                entity.toDomain(json, partner)
                            }
                        }
                    }
                    .collect { orders ->
                        val latestActive = orders.firstOrNull { it.orderStatus.isActive }
                        if (latestActive != null) {
                            dismissedDeliveredOrderId = null
                            _activeTrackedOrder.value = latestActive
                            startTracking(latestActive.id)
                        } else {
                            val previousTrackedId = _activeTrackedOrder.value?.id ?: activeOrderId
                            val deliveredOrder = previousTrackedId?.let { id ->
                                orders.firstOrNull { it.id == id && it.orderStatus == OrderStatus.DELIVERED }
                            }
                            if (deliveredOrder != null && deliveredOrder.id != dismissedDeliveredOrderId) {
                                _activeTrackedOrder.value = deliveredOrder
                                finalizeTerminalTracking(keepDeliveredOrder = true)
                            } else {
                                finalizeTerminalTracking(keepDeliveredOrder = false)
                            }
                        }
                    }
            }

            if (connectivityWatcherJob?.isActive != true) {
                connectivityWatcherJob = scope.launch {
                    connectivityObserver.status.collect { status ->
                        val trackedId = synchronized(lifecycleLock) { activeOrderId } ?: return@collect
                        when (status) {
                            ConnectivityStatus.OFFLINE -> {
                                eventSource.forceDisconnectForTest()
                            }
                            ConnectivityStatus.RESTORED, ConnectivityStatus.ONLINE -> {
                                val wsState = eventSource.connectionState.value
                                if (wsState == WebSocketConnectionState.DISCONNECTED || wsState == WebSocketConnectionState.ERROR) {
                                    val entity = orderDao.getOrder(trackedId)
                                    val currentStatus = entity?.let {
                                        runCatching { OrderStatus.valueOf(it.orderStatus) }.getOrNull()
                                    }
                                    if (entity != null && currentStatus != null && currentStatus.isActive) {
                                        reconcileOrder(trackedId)
                                        eventSource.reconnect()
                                        if (eventSource.connectionState.value == WebSocketConnectionState.DISCONNECTED) {
                                            eventSource.connect(
                                                orderId = trackedId,
                                                startFromStatus = currentStatus,
                                                lastSequence = entity.lastEventSequence.coerceAtLeast(2L),
                                                restLat = entity.restaurantLat,
                                                restLng = entity.restaurantLng,
                                                destLat = entity.deliveryLat,
                                                destLng = entity.deliveryLng
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reconciles and reconnects tracking for the active order after a pause. */
    override fun resumeActiveOrderTracking() {
        ensureActiveOrderTracking()
        val trackedId = synchronized(lifecycleLock) { activeOrderId } ?: return
        scope.launch {
            val entity = orderDao.getOrder(trackedId) ?: return@launch
            val currentStatus = runCatching { OrderStatus.valueOf(entity.orderStatus) }
                .getOrDefault(OrderStatus.PLACED)
            if (currentStatus.isTerminal) {
                val partner = entity.deliveryPartnerId?.let { catalogDao.getDeliveryPartner(it)?.toDomain() }
                val domain = entity.toDomain(json, partner)
                if (currentStatus == OrderStatus.DELIVERED && domain.id != dismissedDeliveredOrderId) {
                    _activeTrackedOrder.value = domain
                }
                finalizeTerminalTracking(keepDeliveredOrder = currentStatus == OrderStatus.DELIVERED)
                return@launch
            }
            if (!connectivityObserver.isCurrentlyOnline()) return@launch

            reconcileOrder(trackedId)
            val wsState = eventSource.connectionState.value
            if (wsState == WebSocketConnectionState.DISCONNECTED || wsState == WebSocketConnectionState.ERROR) {
                eventSource.connect(
                    orderId = trackedId,
                    startFromStatus = currentStatus,
                    lastSequence = entity.lastEventSequence.coerceAtLeast(2L),
                    restLat = entity.restaurantLat,
                    restLng = entity.restaurantLng,
                    destLat = entity.deliveryLat,
                    destLng = entity.deliveryLng
                )
            }
        }
    }

    /** Hides the banner for the delivered order currently being tracked. */
    override fun dismissDeliveredOrderBanner() {
        val current = _activeTrackedOrder.value ?: return
        if (current.orderStatus == OrderStatus.DELIVERED) {
            dismissedDeliveredOrderId = current.id
            _activeTrackedOrder.value = null
        }
    }

    /** Starts a single update collector and event-source connection for this order. */
    override fun startTracking(orderId: String) {
        synchronized(lifecycleLock) {
            if (activeOrderId == orderId && collectorJob?.isActive == true) {
                // Already subscribed to this order — ensure connection is healthy if online,
                // without creating duplicate collectors or tearing down an active WebSocket.
                val wsState = eventSource.connectionState.value
                if ((wsState == WebSocketConnectionState.DISCONNECTED || wsState == WebSocketConnectionState.ERROR) &&
                    connectivityObserver.isCurrentlyOnline()
                ) {
                    eventSource.reconnect()
                }
                return
            }

            stopActiveCollectorOnly()
            activeOrderId = orderId
            _activeTrackedOrderId.value = orderId
            activeCollectorLaunches += 1

            collectorJob = scope.launch {
                val orderEntity = orderDao.getOrder(orderId) ?: run {
                    finalizeTerminalTracking(keepDeliveredOrder = false)
                    return@launch
                }
                val currentStatus = runCatching { OrderStatus.valueOf(orderEntity.orderStatus) }
                    .getOrDefault(OrderStatus.PLACED)
                val partner = orderEntity.deliveryPartnerId?.let { catalogDao.getDeliveryPartner(it)?.toDomain() }
                val domainOrder = orderEntity.toDomain(json, partner)

                if (currentStatus.isTerminal) {
                    if (currentStatus == OrderStatus.DELIVERED && domainOrder.id != dismissedDeliveredOrderId) {
                        _activeTrackedOrder.value = domainOrder
                    }
                    finalizeTerminalTracking(keepDeliveredOrder = currentStatus == OrderStatus.DELIVERED)
                    return@launch
                }

                _activeTrackedOrder.value = domainOrder

                launch {
                    eventSource.events.collect { event ->
                        processIncomingEvent(expectedOrderId = orderId, event = event)
                    }
                }

                if (connectivityObserver.isCurrentlyOnline()) {
                    eventSource.connect(
                        orderId = orderId,
                        startFromStatus = currentStatus,
                        lastSequence = orderEntity.lastEventSequence.coerceAtLeast(2L),
                        restLat = orderEntity.restaurantLat,
                        restLng = orderEntity.restaurantLng,
                        destLat = orderEntity.deliveryLat,
                        destLng = orderEntity.deliveryLng
                    )
                }
            }
        }
    }

    /** Stops the screen's active tracking collector and clears active-order display state. */
    override fun stopTracking() {
        synchronized(lifecycleLock) {
            stopActiveCollectorOnly()
            if (_activeTrackedOrder.value?.orderStatus?.isActive == true) {
                _activeTrackedOrder.value = null
            }
        }
    }

    /** Stops all tracking observers and collectors for deterministic test cleanup. */
    internal fun shutdownAllWatchersForTest() {
        synchronized(lifecycleLock) {
            activeOrderWatcherJob?.cancel()
            activeOrderWatcherJob = null
            connectivityWatcherJob?.cancel()
            connectivityWatcherJob = null
            stopActiveCollectorOnly()
            _activeTrackedOrder.value = null
        }
    }

    /** Cancels the active order collector and disconnects its event source. */
    private fun stopActiveCollectorOnly() {
        collectorJob?.cancel()
        collectorJob = null
        activeOrderId = null
        _activeTrackedOrderId.value = null
        eventSource.disconnect()
    }

    /** Cleans up tracking after a terminal status, optionally keeping a delivered order visible. */
    private fun finalizeTerminalTracking(keepDeliveredOrder: Boolean) {
        synchronized(lifecycleLock) {
            collectorJob?.cancel()
            collectorJob = null
            activeOrderId = null
            _activeTrackedOrderId.value = null
            eventSource.disconnect()
            if (!keepDeliveredOrder) {
                _activeTrackedOrder.value = null
            }
        }
    }

    /** Reconnects the event source for the active order when connection is lost. */
    override fun reconnect() {
        val trackedId = synchronized(lifecycleLock) { activeOrderId }
        if (trackedId == null) {
            eventSource.reconnect()
            return
        }
        scope.launch {
            val entity = orderDao.getOrder(trackedId) ?: return@launch
            val status = runCatching { OrderStatus.valueOf(entity.orderStatus) }.getOrDefault(OrderStatus.PLACED)
            if (status.isTerminal) {
                finalizeTerminalTracking(keepDeliveredOrder = status == OrderStatus.DELIVERED)
                return@launch
            }
            if (eventSource.connectionState.value == WebSocketConnectionState.DISCONNECTED) {
                eventSource.connect(
                    orderId = trackedId,
                    startFromStatus = status,
                    lastSequence = entity.lastEventSequence.coerceAtLeast(2L),
                    restLat = entity.restaurantLat,
                    restLng = entity.restaurantLng,
                    destLat = entity.deliveryLat,
                    destLng = entity.deliveryLng
                )
            } else {
                eventSource.reconnect()
            }
        }
    }

    /** Requests the local event simulator to emit its next order-status step. */
    override fun advanceNextStep() {
        eventSource.emitManualNextStep()
    }

    /**
     * Exposed internally for deterministic unit testing of event ordering,
     * duplicate filtering, illegal transition rejection, and terminal state cleanup.
     */
    internal suspend fun processIncomingEvent(expectedOrderId: String, event: OrderEvent): Boolean =
        eventMutex.withLock {
            // 1. Validate Order ID match
            if (event.orderId != expectedOrderId) {
                Log.w(TAG, "Ignored event for mismatched orderId ${event.orderId} != $expectedOrderId")
                return@withLock false
            }

            val current = orderDao.getOrder(expectedOrderId) ?: return@withLock false

            // 2. Sequence number guard: reject duplicates & stale out-of-order events
            if (event.sequenceNumber == current.lastEventSequence) {
                _diagnostics.value = _diagnostics.value.copy(
                    lastIgnoredEventNote = "Duplicate event #${event.sequenceNumber} (${event.type.name}) ignored",
                    duplicatesIgnoredCount = _diagnostics.value.duplicatesIgnoredCount + 1
                )
                return@withLock false
            }
            if (event.sequenceNumber < current.lastEventSequence) {
                _diagnostics.value = _diagnostics.value.copy(
                    lastIgnoredEventNote = "Stale out-of-order event #${event.sequenceNumber} < #${current.lastEventSequence} ignored",
                    staleIgnoredCount = _diagnostics.value.staleIgnoredCount + 1
                )
                return@withLock false
            }

            val currentStatus = runCatching { OrderStatus.valueOf(current.orderStatus) }
                .getOrDefault(OrderStatus.PLACED)

            // 3. Validate OrderStatus state-machine transition when event alters status
            val targetStatus = event.type.mappedStatus
            val nextStatus = if (targetStatus != null) {
                if (currentStatus.canTransitionTo(targetStatus)) {
                    targetStatus
                } else {
                    _diagnostics.value = _diagnostics.value.copy(
                        lastIgnoredEventNote = "Illegal transition $currentStatus -> $targetStatus blocked",
                        staleIgnoredCount = _diagnostics.value.staleIgnoredCount + 1
                    )
                    return@withLock false
                }
            } else {
                currentStatus
            }

            val isDelayed = !nextStatus.isTerminal &&
                (scenarioController.current().delayedOrderActive || event.type == OrderEventType.ORDER_DELAYED)
            val resolvedEta = when {
                nextStatus == OrderStatus.DELIVERED -> 0
                event.etaMinutes != null -> event.etaMinutes
                else -> current.estimatedDeliveryMinutes
            }

            val updated = current.copy(
                orderStatus = nextStatus.name,
                deliveryPartnerId = event.partnerId ?: current.deliveryPartnerId,
                riderLat = event.latitude ?: current.riderLat,
                riderLng = event.longitude ?: current.riderLng,
                riderProgress = event.progressFraction ?: if (nextStatus == OrderStatus.DELIVERED) 1.0f else current.riderProgress,
                riderUpdatedAtMs = event.timestampMs,
                estimatedDeliveryMinutes = resolvedEta,
                isDelayed = isDelayed,
                delayReason = if (isDelayed) "High kitchen volume & peak-hour traffic" else null,
                lastEventSequence = event.sequenceNumber,
                updatedAtMs = event.timestampMs
            )

            val timelineEntity = OrderTimelineEventEntity(
                id = event.eventId,
                orderId = event.orderId,
                sequenceNumber = event.sequenceNumber,
                eventType = event.type.name,
                title = event.type.title,
                description = event.message,
                timestampMs = event.timestampMs
            )

            // 4. Commit order state + timeline event atomically in Room
            orderDao.applyTrackingEvent(updated, timelineEntity)

            val partner = updated.deliveryPartnerId?.let { catalogDao.getDeliveryPartner(it)?.toDomain() }
            val updatedDomain = updated.toDomain(json, partner)
            if (nextStatus != OrderStatus.CANCELLED) {
                _activeTrackedOrder.value = updatedDomain
            }

            // 5. Terminate active WebSocket ONLY AFTER terminal status has been committed & published
            if (nextStatus.isTerminal) {
                finalizeTerminalTracking(keepDeliveredOrder = nextStatus == OrderStatus.DELIVERED)
            }
            return@withLock true
        }

    /**
     * Reconciles missed events via REST order details lookup after WebSocket reconnect.
     */
    override suspend fun reconcileOrder(orderId: String): AppResult<Order> = withContext(dispatchers.io) {
        val local = orderDao.getOrder(orderId)
            ?: return@withContext AppResult.Error(AppError.EmptyResponse("Order $orderId not cached locally."))

        when (val remoteRes = orderRemote.getOrder(orderId)) {
            is AppResult.Success -> {
                val remote = remoteRes.data
                val remoteStatus = runCatching { OrderStatus.valueOf(remote.orderStatus) }.getOrDefault(OrderStatus.PLACED)
                val localStatus = runCatching { OrderStatus.valueOf(local.orderStatus) }.getOrDefault(OrderStatus.PLACED)

                val reconciledStatus = if (localStatus.canTransitionTo(remoteStatus)) remoteStatus else localStatus
                val reconciledEta = if (reconciledStatus == OrderStatus.DELIVERED) {
                    0
                } else {
                    remote.estimatedDeliveryMinutes
                }
                val updated = local.copy(
                    orderStatus = reconciledStatus.name,
                    paymentStatus = remote.paymentStatus,
                    estimatedDeliveryMinutes = reconciledEta,
                    updatedAtMs = System.currentTimeMillis()
                )
                orderDao.upsertOrder(updated)
                val partner = updated.deliveryPartnerId?.let { catalogDao.getDeliveryPartner(it)?.toDomain() }
                val domain = updated.toDomain(json, partner)
                if (reconciledStatus.isActive || reconciledStatus == OrderStatus.DELIVERED) {
                    _activeTrackedOrder.value = domain
                }
                if (reconciledStatus.isTerminal) {
                    finalizeTerminalTracking(keepDeliveredOrder = reconciledStatus == OrderStatus.DELIVERED)
                }
                AppResult.Success(domain)
            }
            is AppResult.Error -> remoteRes
        }
    }

    companion object {
        private const val TAG = "OrderTrackingRepo"
    }
}
