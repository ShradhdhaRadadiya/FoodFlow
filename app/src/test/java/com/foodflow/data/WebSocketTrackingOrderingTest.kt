package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.core.websocket.LocalSimulatedOrderEventSource
import com.foodflow.core.websocket.OrderEvent
import com.foodflow.core.websocket.OrderEventType
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.data.repository.OrderTrackingRepositoryImpl
import com.foodflow.domain.model.OrderStatus
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WebSocketTrackingOrderingTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var orderRemote: OrderRemoteDataSource
    private lateinit var trackingRepo: OrderTrackingRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val connectivity = FakeConnectivityObserver(true)
        val scenarioController = MockScenarioController(connectivity)
        val eventSource = LocalSimulatedOrderEventSource(dispatchers)
        val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
        every { prefs.preferences } returns flowOf(UserPreferences(userId = "u1"))
        orderRemote = mockk(relaxed = true)

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

        db.orderDao().upsertOrder(
            OrderEntity(
                id = "FF-TRACK-1", userId = "u1", idempotencyKey = "idem_track_1",
                restaurantId = "r1", restaurantName = "Spice Route", restaurantAddress = "SG Hwy",
                restaurantLat = 23.02, restaurantLng = 72.57, deliveryAddress = "Satellite",
                deliveryLat = 23.04, deliveryLng = 72.53, deliveryInstructions = "", itemsJson = "[]",
                subtotal = 300.0, deliveryFee = 30.0, packagingCharge = 12.0, taxes = 15.0,
                discount = 0.0, totalPayable = 357.0, couponCode = null, orderStatus = "PLACED",
                paymentStatus = "SUCCESS", paymentMethod = "UPI", estimatedDeliveryMinutes = 25,
                isDelayed = false, delayReason = null, deliveryPartnerId = null, riderLat = null,
                riderLng = null, riderProgress = 0f, riderUpdatedAtMs = 0L, lastEventSequence = 2L,
                ratingGiven = null, reviewComment = null, createdAtMs = 100L, updatedAtMs = 100L
            )
        )
    }

    @After
    fun tearDown() {
        trackingRepo.shutdownAllWatchersForTest()
        db.close()
    }

    @Test
    fun `valid events update Room while duplicate stale mismatched and illegal-jump events are rejected`() = runTest(testDispatcher) {
        // 1. Mismatched orderId -> rejected
        val wrongOrder = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e_wrong", "FF-OTHER", 3L, OrderEventType.ORDER_ACCEPTED, "Wrong order")
        )
        assertFalse(wrongOrder)

        // 2. Illegal direct jump PLACED -> DELIVERED -> blocked even with valid sequence
        val illegalJump = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e_jump", "FF-TRACK-1", 3L, OrderEventType.ORDER_DELIVERED, "Jump")
        )
        assertFalse(illegalJump)
        assertEquals("PLACED", db.orderDao().getOrder("FF-TRACK-1")?.orderStatus)

        // 3. Valid sequence #3 ACCEPTED -> applied
        val accepted = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e3", "FF-TRACK-1", 3L, OrderEventType.ORDER_ACCEPTED, "Kitchen accepted", etaMinutes = 22)
        )
        assertTrue(accepted)
        assertEquals("ACCEPTED", db.orderDao().getOrder("FF-TRACK-1")?.orderStatus)
        assertEquals(3L, db.orderDao().getOrder("FF-TRACK-1")?.lastEventSequence)

        // 4. Duplicate sequence #3 -> ignored
        val duplicate = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e3_dup", "FF-TRACK-1", 3L, OrderEventType.ORDER_ACCEPTED, "Dup")
        )
        assertFalse(duplicate)
        assertEquals(1, trackingRepo.diagnostics.value.duplicatesIgnoredCount)

        // 5. Valid sequence #4 PREPARING -> applied
        val preparing = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e4", "FF-TRACK-1", 4L, OrderEventType.FOOD_PREPARING, "Chef cooking")
        )
        assertTrue(preparing)
        assertEquals("PREPARING", db.orderDao().getOrder("FF-TRACK-1")?.orderStatus)

        // 6. Stale out-of-order sequence #2 -> ignored
        val stale = trackingRepo.processIncomingEvent(
            expectedOrderId = "FF-TRACK-1",
            event = OrderEvent("e2_stale", "FF-TRACK-1", 2L, OrderEventType.ORDER_ACCEPTED, "Stale")
        )
        assertFalse(stale)
        assertEquals("PREPARING", db.orderDao().getOrder("FF-TRACK-1")?.orderStatus)
        assertTrue(trackingRepo.diagnostics.value.staleIgnoredCount >= 1)

        // Verify timeline only recorded the 2 valid events
        val timeline = db.orderDao().observeTimeline("FF-TRACK-1").first()
        assertEquals(listOf(3L, 4L), timeline.map { it.sequenceNumber })
    }

    @Test
    fun `reconcileOrder catches up missed status via REST without regressing local state`() = runTest(testDispatcher) {
        // Advance local state to ACCEPTED
        trackingRepo.processIncomingEvent(
            "FF-TRACK-1",
            OrderEvent("e3", "FF-TRACK-1", 3L, OrderEventType.ORDER_ACCEPTED, "Accepted")
        )

        // Remote server is ahead at PICKED_UP -> reconcile catches up
        coEvery { orderRemote.getOrder("FF-TRACK-1") } returns AppResult.Success(
            OrderDto(
                id = "FF-TRACK-1", userId = "u1", idempotencyKey = "idem_track_1",
                restaurantId = "r1", restaurantName = "Spice Route", restaurantAddress = "SG Hwy",
                restaurantLat = 23.02, restaurantLng = 72.57, deliveryAddress = "Satellite",
                deliveryLat = 23.04, deliveryLng = 72.53, deliveryInstructions = "", items = emptyList(),
                subtotal = 300.0, deliveryFee = 30.0, packagingCharge = 12.0, taxes = 15.0,
                discount = 0.0, totalPayable = 357.0, couponCode = null, orderStatus = "PICKED_UP",
                paymentStatus = "SUCCESS", paymentMethod = "UPI", estimatedDeliveryMinutes = 12
            )
        )

        val reconciled = trackingRepo.reconcileOrder("FF-TRACK-1")
        assertTrue(reconciled is AppResult.Success)
        assertEquals(OrderStatus.PICKED_UP, (reconciled as AppResult.Success).data.orderStatus)
    }
}
