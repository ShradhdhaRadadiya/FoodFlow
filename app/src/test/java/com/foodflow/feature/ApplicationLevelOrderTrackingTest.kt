package com.foodflow.feature

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.core.location.RoomDeliveryLocationProvider
import com.foodflow.core.websocket.LocalSimulatedOrderEventSource
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.DeliveryPartnerEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.data.repository.OrderRepositoryImpl
import com.foodflow.data.repository.OrderTrackingRepositoryImpl
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.repository.AddressRepository
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.DeliveryRouteRepository
import com.foodflow.domain.repository.RestaurantRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.order.CancelOrderUseCase
import com.foodflow.domain.usecase.order.ObserveOrderTimelineUseCase
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.order.RefreshOrdersUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCategoriesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.tracking.AdvanceOrderTrackingStepUseCase
import com.foodflow.domain.usecase.tracking.DismissDeliveredOrderBannerUseCase
import com.foodflow.domain.usecase.tracking.EnsureActiveOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.GetDeliveryRouteUseCase
import com.foodflow.domain.usecase.tracking.ObserveActiveTrackedOrderUseCase
import com.foodflow.domain.usecase.tracking.ObserveDeliveryLocationUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingConnectionUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingDiagnosticsUseCase
import com.foodflow.domain.usecase.tracking.ReconcileOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.ReconnectOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StartOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StopOrderTrackingUseCase
import com.foodflow.feature.home.HomeIntent
import com.foodflow.feature.home.HomeViewModel
import com.foodflow.feature.tracking.OrderTrackingIntent
import com.foodflow.feature.tracking.OrderTrackingViewModel
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * End-to-end integration tests verifying application-scoped real-time order tracking
 * across Dashboard (HomeViewModel) and Track Order (OrderTrackingViewModel):
 * - Scenarios 1–6: Live progression through Placed -> Accepted -> Preparing ->
 *   Ready for Pickup -> Partner Assigned -> Picked Up -> On the Way (ETA updates) -> Delivered
 *   while staying on Dashboard without reopening Track Order.
 * - Scenario 7: Repeated navigation between Dashboard and Track Order causes zero interruptions.
 * - Scenario 8: App minimize/resume keeps tracking active and synchronized.
 * - Scenario 9: Network drop and restore reconnects and completes tracking cleanly.
 * - Scenario 10: Zero duplicate WebSocket connections or duplicate event collectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ApplicationLevelOrderTrackingTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var eventSource: LocalSimulatedOrderEventSource
    private lateinit var orderRemote: OrderRemoteDataSource
    private lateinit var orderRepo: OrderRepositoryImpl
    private lateinit var trackingRepo: OrderTrackingRepositoryImpl
    private lateinit var restaurantRepo: RestaurantRepository
    private lateinit var addressRepo: AddressRepository
    private lateinit var routeRepo: DeliveryRouteRepository

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { runnable -> runnable.run() }
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()

        connectivity = FakeConnectivityObserver(initialOnline = true)
        val scenarioController = MockScenarioController(connectivity)
        eventSource = LocalSimulatedOrderEventSource(dispatchers).apply {
            setEventIntervalMs(200L)
        }

        val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
        val userPrefs = UserPreferences(userId = "u_live", authToken = "tok")
        every { prefs.preferences } returns flowOf(userPrefs)
        coEvery { prefs.getSnapshot() } returns userPrefs

        orderRemote = mockk(relaxed = true)
        coEvery { orderRemote.getOrder(any()) } answers {
            val id = firstArg<String>()
            AppResult.Success(
                OrderDto(
                    id = id, userId = "u_live", idempotencyKey = "idem_$id",
                    restaurantId = "r1", restaurantName = "Spice Route", restaurantAddress = "SG Hwy",
                    restaurantLat = 23.02, restaurantLng = 72.57, deliveryAddress = "Satellite",
                    deliveryLat = 23.04, deliveryLng = 72.53, deliveryInstructions = "", items = emptyList(),
                    subtotal = 300.0, deliveryFee = 30.0, packagingCharge = 12.0, taxes = 15.0,
                    discount = 0.0, totalPayable = 357.0, couponCode = null, orderStatus = "PREPARING",
                    paymentStatus = "SUCCESS", paymentMethod = "UPI", estimatedDeliveryMinutes = 18
                )
            )
        }

        orderRepo = OrderRepositoryImpl(
            orderDao = db.orderDao(),
            cartDao = db.cartDao(),
            catalogDao = db.catalogDao(),
            addressDao = db.addressDao(),
            orderRemote = orderRemote,
            cartRepository = mockk<CartRepository>(relaxed = true),
            calculateCartTotals = CalculateCartTotalsUseCase(),
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            json = json,
            dispatchers = dispatchers
        )

        trackingRepo = OrderTrackingRepositoryImpl(
            eventSource = eventSource,
            orderDao = db.orderDao(),
            catalogDao = db.catalogDao(),
            orderRemote = orderRemote,
            scenarioController = scenarioController,
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            json = json,
            dispatchers = dispatchers
        )

        restaurantRepo = mockk(relaxed = true)
        every { restaurantRepo.observeCategories() } returns flowOf(emptyList())
        every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
        every { restaurantRepo.observeFavoriteRestaurants() } returns flowOf(emptyList())
        coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Success(1L)

        addressRepo = mockk(relaxed = true)
        every { addressRepo.observeSelectedAddress() } returns flowOf(null)

        routeRepo = mockk(relaxed = true)

        db.catalogDao().upsertDeliveryPartners(
            listOf(
                DeliveryPartnerEntity(
                    id = "partner_1",
                    name = "Rajesh Patel",
                    phone = "+91 9876543210",
                    rating = 4.9,
                    vehicleNumber = "GJ-01-FF-4021",
                    totalDeliveries = 420
                )
            )
        )
    }

    @After
    fun tearDown() {
        trackingRepo.shutdownAllWatchersForTest()
        db.close()
        Dispatchers.resetMain()
    }

    private fun createHomeViewModel(): HomeViewModel = HomeViewModel(
        observeCategories = ObserveCategoriesUseCase(restaurantRepo),
        observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
        observeFavoriteRestaurants = com.foodflow.domain.usecase.restaurant.ObserveFavoriteRestaurantsUseCase(restaurantRepo),
        observeSelectedAddress = ObserveSelectedAddressUseCase(addressRepo),
        observeOrders = ObserveOrdersUseCase(orderRepo),
        observeActiveTrackedOrder = ObserveActiveTrackedOrderUseCase(trackingRepo),
        ensureActiveOrderTracking = EnsureActiveOrderTrackingUseCase(trackingRepo),
        dismissDeliveredOrderBanner = DismissDeliveredOrderBannerUseCase(trackingRepo),
        refreshHomeCatalog = RefreshHomeCatalogUseCase(restaurantRepo),
        refreshOrders = RefreshOrdersUseCase(orderRepo),
        toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(restaurantRepo),
        connectivityObserver = connectivity
    )

    private fun createTrackingViewModel(): OrderTrackingViewModel = OrderTrackingViewModel(
        observeOrder = ObserveOrderUseCase(orderRepo),
        observeActiveTrackedOrder = ObserveActiveTrackedOrderUseCase(trackingRepo),
        observeOrderTimeline = ObserveOrderTimelineUseCase(orderRepo),
        observeDeliveryLocation = ObserveDeliveryLocationUseCase(RoomDeliveryLocationProvider(orderRepo)),
        observeTrackingConnection = ObserveTrackingConnectionUseCase(trackingRepo),
        observeTrackingDiagnostics = ObserveTrackingDiagnosticsUseCase(trackingRepo),
        startOrderTracking = StartOrderTrackingUseCase(trackingRepo),
        stopOrderTracking = StopOrderTrackingUseCase(trackingRepo),
        reconnectOrderTracking = ReconnectOrderTrackingUseCase(trackingRepo),
        reconcileOrderTracking = ReconcileOrderTrackingUseCase(trackingRepo),
        getDeliveryRoute = GetDeliveryRouteUseCase(routeRepo),
        cancelOrderUseCase = CancelOrderUseCase(orderRepo),
        connectivityObserver = connectivity
    )

    private suspend fun insertActiveOrder(id: String, initialStatus: OrderStatus = OrderStatus.PLACED) {
        db.orderDao().upsertOrder(
            OrderEntity(
                id = id, userId = "u_live", idempotencyKey = "idem_$id",
                restaurantId = "r1", restaurantName = "Spice Route Kitchen", restaurantAddress = "SG Hwy",
                restaurantLat = 23.0225, restaurantLng = 72.5714, deliveryAddress = "14 Satellite Road",
                deliveryLat = 23.0469, deliveryLng = 72.5310, deliveryInstructions = "", itemsJson = "[]",
                subtotal = 300.0, deliveryFee = 30.0, packagingCharge = 12.0, taxes = 15.0,
                discount = 0.0, totalPayable = 357.0, couponCode = null, orderStatus = initialStatus.name,
                paymentStatus = "SUCCESS", paymentMethod = "UPI", estimatedDeliveryMinutes = 25,
                isDelayed = false, delayReason = null, deliveryPartnerId = null, riderLat = null,
                riderLng = null, riderProgress = 0f, riderUpdatedAtMs = 0L, lastEventSequence = 2L,
                ratingGiven = null, reviewComment = null, createdAtMs = 100L, updatedAtMs = 100L
            )
        )
    }

    @Test
    fun `dashboard banner receives live updates through Delivered even after leaving Track Order screen`() = runTest(testDispatcher) {
        insertActiveOrder("FF-LIVE-1", OrderStatus.PLACED)

        // App starts global tracking + Dashboard opens
        trackingRepo.ensureActiveOrderTracking()
        val homeVm = createHomeViewModel()
        val trackingVm = createTrackingViewModel()
        trackingVm.onIntent(OrderTrackingIntent.Start("FF-LIVE-1"))
        runCurrent()

        // Advance past initial connection (350ms) + ACCEPTED (200ms) + PREPARING (200ms) + READY_FOR_PICKUP (200ms)
        advanceTimeBy(1_050L)
        runCurrent()

        assertEquals(OrderStatus.READY_FOR_PICKUP, homeVm.state.value.activeOrder?.orderStatus)
        assertEquals(OrderStatus.READY_FOR_PICKUP, trackingVm.state.value.order?.orderStatus)

        // Simulate user navigating BACK from Track Order to Dashboard (Stop intent / ViewModel detached)
        trackingVm.onIntent(OrderTrackingIntent.Stop)

        // Stay on Dashboard while PARTNER_ASSIGNED (200ms) + PICKED_UP (200ms) arrive
        advanceTimeBy(420L)
        runCurrent()

        assertEquals(OrderStatus.PICKED_UP, homeVm.state.value.activeOrder?.orderStatus)
        val etaAtPickup = homeVm.state.value.activeOrder?.estimatedDeliveryMinutes ?: 99
        assertTrue("Expected dynamic ETA update on pickup", etaAtPickup in 1..22)

        // Stay on Dashboard while RIDER_LOCATION_UPDATED ticks arrive (3x 200ms) -> ETA decreases live
        advanceTimeBy(620L)
        runCurrent()
        assertEquals(OrderStatus.PICKED_UP, homeVm.state.value.activeOrder?.orderStatus)
        val etaNearDoorstep = homeVm.state.value.activeOrder?.estimatedDeliveryMinutes ?: 99
        assertTrue("Expected ETA to decrease as rider approaches ($etaNearDoorstep < $etaAtPickup)", etaNearDoorstep < etaAtPickup)

        // Stay on Dashboard until DELIVERED arrives -> Delivered banner appears and WebSocket cleanly disconnects
        advanceTimeBy(400L)
        runCurrent()

        assertNull(homeVm.state.value.activeOrder)
        assertNotNull(homeVm.state.value.deliveredOrderBanner)
        assertEquals(OrderStatus.DELIVERED, homeVm.state.value.deliveredOrderBanner?.orderStatus)
        assertEquals(0, homeVm.state.value.deliveredOrderBanner?.estimatedDeliveryMinutes)
        assertEquals(WebSocketConnectionState.DISCONNECTED, trackingRepo.connectionState.value)
        assertNull(trackingRepo.activeTrackedOrderId.value)

        // Dismissing delivered banner clears it cleanly
        homeVm.onIntent(HomeIntent.DismissDeliveredBanner)
        runCurrent()
        assertNull(homeVm.state.value.deliveredOrderBanner)
    }

    @Test
    fun `repeated navigation and app resume create zero duplicate WebSocket connections or collectors`() = runTest(testDispatcher) {
        insertActiveOrder("FF-LIVE-2", OrderStatus.PREPARING)

        // Simulate FoodFlowApp.onCreate + HomeViewModel + MainActivity.onStart + repeated Track Order visits
        trackingRepo.ensureActiveOrderTracking()
        val homeVm = createHomeViewModel()
        runCurrent()
        advanceTimeBy(400L)
        runCurrent()

        repeat(5) {
            val trackingVm = createTrackingViewModel()
            trackingVm.onIntent(OrderTrackingIntent.Start("FF-LIVE-2"))
            runCurrent()
            trackingVm.onIntent(OrderTrackingIntent.Stop)
            trackingRepo.resumeActiveOrderTracking()
            runCurrent()
        }

        // Exactly ONE WebSocket connection and ONE collector should ever have been created
        assertEquals(1, eventSource.connectInvocationCount)
        assertEquals(1, trackingRepo.activeCollectorLaunches)
        assertEquals(WebSocketConnectionState.CONNECTED, trackingRepo.connectionState.value)
        assertNotNull(homeVm.state.value.activeOrder)

        // Finish stream cleanly so no pending coroutines remain
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(OrderStatus.DELIVERED, homeVm.state.value.deliveredOrderBanner?.orderStatus)
    }

    @Test
    fun `network disconnect and reconnect automatically recovers tracking and updates Dashboard to Delivered`() = runTest(testDispatcher) {
        insertActiveOrder("FF-LIVE-3", OrderStatus.ACCEPTED)

        trackingRepo.ensureActiveOrderTracking()
        val homeVm = createHomeViewModel()
        runCurrent()

        // Connect (350ms) + advance to PREPARING (200ms)
        advanceTimeBy(600L)
        runCurrent()
        assertEquals(OrderStatus.PREPARING, homeVm.state.value.activeOrder?.orderStatus)

        // Simulate network loss
        connectivity.setSimulatedOffline(true)
        runCurrent()
        assertEquals(WebSocketConnectionState.DISCONNECTED, trackingRepo.connectionState.value)

        // Simulate network restored -> auto reconnect + reconcile resumes progression through Delivered
        connectivity.setSimulatedOffline(false)
        runCurrent()
        advanceTimeBy(3_500L)
        runCurrent()

        assertEquals(OrderStatus.DELIVERED, homeVm.state.value.deliveredOrderBanner?.orderStatus)
        assertEquals(WebSocketConnectionState.DISCONNECTED, trackingRepo.connectionState.value)
    }
}
