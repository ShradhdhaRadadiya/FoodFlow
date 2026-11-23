package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.mock.SimulatedPaymentOutcome
import com.foodflow.data.repository.OrderRepositoryImpl
import com.foodflow.data.repository.PaymentRepositoryImpl
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class IdempotencyAndPaymentTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var scenarioController: MockScenarioController
    private lateinit var mockBackend: LocalMockBackend
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var paymentRepository: PaymentRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        connectivity = FakeConnectivityObserver(initialOnline = true)
        scenarioController = MockScenarioController(connectivity).apply {
            setLatencyMs(0L)
        }

        val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
        val defaultPrefs = UserPreferences(userId = "user_test_1", authToken = "tok_test")
        every { prefs.preferences } returns flowOf(defaultPrefs)
        coEvery { prefs.getSnapshot() } returns defaultPrefs

        val seeder = mockk<DatabaseSeeder>(relaxed = true)
        coEvery { seeder.loadSeedDataset() } returns null

        mockBackend = LocalMockBackend(
            connectivityObserver = connectivity,
            scenarioController = scenarioController,
            seeder = seeder,
            catalogDao = db.catalogDao(),
            serverStateDao = db.mockServerStateDao(),
            json = json
        )

        val cartRepo = mockk<CartRepository>(relaxed = true)
        orderRepository = OrderRepositoryImpl(
            orderDao = db.orderDao(),
            cartDao = db.cartDao(),
            catalogDao = db.catalogDao(),
            addressDao = db.addressDao(),
            orderRemote = mockBackend,
            cartRepository = cartRepo,
            calculateCartTotals = CalculateCartTotalsUseCase(),
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            json = json,
            dispatchers = dispatchers
        )
        paymentRepository = PaymentRepositoryImpl(
            paymentRemote = mockBackend,
            orderDao = db.orderDao(),
            cartDao = db.cartDao(),
            preferencesDataStore = prefs,
            dispatchers = dispatchers
        )

        // Seed restaurant, serviceable address, and cart item
        db.catalogDao().upsertRestaurants(
            listOf(
                RestaurantEntity(
                    id = "rest_1", name = "Spice Route", cuisinesCsv = "North Indian",
                    rating = 4.6, ratingCount = 500, deliveryTimeMin = 25, deliveryFee = 30.0,
                    minOrderAmount = 149.0, offerText = null, isPureVeg = false, isOpen = true,
                    isPopular = true, isRecommended = true, imageKey = "indian",
                    address = "SG Hwy", distanceKm = 2.0, latitude = 23.02, longitude = 72.57,
                    lastUpdatedMs = 1L
                )
            )
        )
        db.addressDao().upsertAddress(
            AddressEntity(
                id = "addr_1", userId = "user_test_1", label = "HOME", recipientName = "Aarav",
                line1 = "14 Satellite", landmark = "Near Mall", city = "Ahmedabad",
                pincode = "380015", deliveryInstructions = "Ring bell", latitude = 23.03,
                longitude = 72.53, isDefault = true, isServiceable = true
            )
        )
        db.cartDao().upsertCartItem(
            CartItemEntity(
                itemId = "food_1", userId = "user_test_1", restaurantId = "rest_1",
                restaurantName = "Spice Route", foodName = "Paneer Tikka", unitPrice = 300.0,
                quantity = 1, isVeg = true, imageKey = "indian", selectedAddonsJson = "[]",
                cookingInstructions = "", syncStatus = "SYNCED", updatedAtMs = 1L
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `repeated checkout submission with same idempotencyKey creates only ONE order`() = runTest(testDispatcher) {
        val attempt1 = (orderRepository.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null) as AppResult.Success).data
        val attempt2 = (orderRepository.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null) as AppResult.Success).data

        // Checkout attempt reuse preserves the exact same idempotencyKey across retries
        assertEquals(attempt1.idempotencyKey, attempt2.idempotencyKey)

        val order1 = (orderRepository.submitIdempotentOrder(attempt1) as AppResult.Success).data
        val order2 = (orderRepository.submitIdempotentOrder(attempt2) as AppResult.Success).data

        assertEquals(order1.id, order2.id)
        val allOrders = db.orderDao().observeOrders("user_test_1").first()
        assertEquals(1, allOrders.size)
    }

    @Test
    fun `timeout after server write recovers original order via idempotency lookup without duplicate`() = runTest(testDispatcher) {
        val attempt = (orderRepository.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null) as AppResult.Success).data

        // Simulate server committing order and response timing out in flight
        scenarioController.setNetworkFault(SimulatedNetworkFault.TIMEOUT_AFTER_SERVER_WRITE)
        val firstSubmit = orderRepository.submitIdempotentOrder(attempt)
        assertTrue(firstSubmit is AppResult.Error)
        assertTrue((firstSubmit as AppResult.Error).error is AppError.Timeout)

        // Local Room DB should not have confirmed the order yet
        assertEquals(0, db.orderDao().observeOrders("user_test_1").first().size)

        // Retry with the SAME persisted attempt recovers the exact order from mock server via GET /orders/by-idempotency-key/{key}
        val retrySubmit = orderRepository.submitIdempotentOrder(attempt)
        assertTrue(retrySubmit is AppResult.Success)
        val recoveredOrder = (retrySubmit as AppResult.Success).data
        assertEquals(attempt.idempotencyKey, recoveredOrder.idempotencyKey)

        // Verify mock server only holds 1 order for this key and Room holds 1 order
        val allOrders = db.orderDao().observeOrders("user_test_1").first()
        assertEquals(1, allOrders.size)
    }

    @Test
    fun `offline checkout is safely blocked and never falsely confirms an order`() = runTest(testDispatcher) {
        scenarioController.setOffline(true)
        val result = orderRepository.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).error is AppError.Offline)
        assertEquals(0, db.orderDao().observeOrders("user_test_1").first().size)
        // Cart remains intact locally
        assertEquals(1, db.cartDao().getCartItems("user_test_1").size)
    }

    @Test
    fun `payment callback deduplication ignores duplicate callbackId and new payment retry settles order`() = runTest(testDispatcher) {
        val attempt = (orderRepository.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null) as AppResult.Success).data
        val order = (orderRepository.submitIdempotentOrder(attempt) as AppResult.Success).data

        // Simulate FAILED payment
        scenarioController.setPaymentOutcome(SimulatedPaymentOutcome.FAILED)
        val firstPay = paymentRepository.processPayment(
            orderId = order.id,
            idempotencyKey = attempt.idempotencyKey,
            method = PaymentMethod.UPI,
            amount = order.totalPayable,
            callbackId = "cb_same_1"
        )
        assertEquals(PaymentStatus.FAILED, (firstPay as AppResult.Success).data)

        // Duplicate callback with same callbackId must be safely ignored even if outcome flips
        scenarioController.setPaymentOutcome(SimulatedPaymentOutcome.SUCCESS)
        val dupPay = paymentRepository.processPayment(
            orderId = order.id,
            idempotencyKey = attempt.idempotencyKey,
            method = PaymentMethod.UPI,
            amount = order.totalPayable,
            callbackId = "cb_same_1"
        )
        assertEquals(PaymentStatus.FAILED, (dupPay as AppResult.Success).data)

        // New payment retry with a fresh callbackId settles order to SUCCESS & clears cart
        val retryPay = paymentRepository.processPayment(
            orderId = order.id,
            idempotencyKey = attempt.idempotencyKey,
            method = PaymentMethod.UPI,
            amount = order.totalPayable,
            callbackId = "cb_retry_2"
        )
        assertEquals(PaymentStatus.SUCCESS, (retryPay as AppResult.Success).data)

        val storedOrder = db.orderDao().getOrder(order.id)
        assertNotNull(storedOrder)
        assertEquals("SUCCESS", storedOrder?.paymentStatus)
        assertEquals(0, db.cartDao().getCartItems("user_test_1").size)
    }
}
