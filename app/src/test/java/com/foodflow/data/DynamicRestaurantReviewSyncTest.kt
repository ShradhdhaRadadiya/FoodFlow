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
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.repository.ReviewRepositoryImpl
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.RestaurantRatingCalculator
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class DynamicRestaurantReviewSyncTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var scenarioController: MockScenarioController
    private lateinit var mockBackend: LocalMockBackend
    private lateinit var reviewRepository: ReviewRepositoryImpl
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
        val defaultPrefs = UserPreferences(
            userId = "user_review_1",
            userName = "Aarav Sharma",
            authToken = "tok_test"
        )
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

        reviewRepository = ReviewRepositoryImpl(
            reviewDao = db.reviewDao(),
            orderDao = db.orderDao(),
            catalogDao = db.catalogDao(),
            reviewRemote = mockBackend,
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            dispatchers = dispatchers
        )

        db.catalogDao().upsertRestaurants(
            listOf(
                sampleRestaurant(id = "rest_100", rating = 4.2, ratingCount = 100),
                sampleRestaurant(id = "rest_zero", rating = 0.0, ratingCount = 0)
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `submitting 5-star review updates 4_2 with 100 reviews to 4_21 with 101 reviews across all screens`() =
        runTest(testDispatcher) {
            seedDeliveredOrder(orderId = "ORD-1", restaurantId = "rest_100")

            val result = reviewRepository.submitReview(
                orderId = "ORD-1",
                rating = 5,
                comment = "Piping hot biryani and crisp naan!"
            )

            assertTrue(result is AppResult.Success)
            val summary = (result as AppResult.Success).data
            assertEquals(4.21, summary.averageRating, 0.001)
            assertEquals(101, summary.totalReviewCount)

            // Verify Home / Listing / Search reactive query reflects updated rating immediately
            val homeRestaurants = db.catalogDao().observeRestaurants().first()
            val homeRest = homeRestaurants.first { it.id == "rest_100" }
            assertEquals(4.21, homeRest.rating, 0.001)
            assertEquals(101, homeRest.ratingCount)

            // Verify Restaurant Detail reactive query reflects identical rating & review record
            val detailSummary = reviewRepository.observeRestaurantRating("rest_100").first()
            assertNotNull(detailSummary)
            assertEquals(4.21, detailSummary!!.averageRating, 0.001)
            assertEquals(101, detailSummary.totalReviewCount)

            val reviews = reviewRepository.observeRestaurantReviews("rest_100").first()
            assertEquals(1, reviews.size)
            assertEquals(5, reviews.first().rating)
            assertEquals("ORD-1", reviews.first().orderId)

            // Verify Orders table updated as well
            val storedOrder = db.orderDao().getOrder("ORD-1")
            assertEquals(5, storedOrder?.ratingGiven)
        }

    @Test
    fun `duplicate review for same order is blocked while editing updates rating without inflating count`() =
        runTest(testDispatcher) {
            seedDeliveredOrder(orderId = "ORD-DUP", restaurantId = "rest_100")

            val first = reviewRepository.submitReview("ORD-DUP", 5, "Loved it")
            assertTrue(first is AppResult.Success)
            assertEquals(4.21, (first as AppResult.Success).data.averageRating, 0.001)
            assertEquals(101, first.data.totalReviewCount)

            // Duplicate submission must be rejected
            val duplicate = reviewRepository.submitReview("ORD-DUP", 1, "Duplicate")
            assertTrue(duplicate is AppResult.Error)
            assertTrue((duplicate as AppResult.Error).error is AppError.Conflict)

            // Aggregate remains unchanged after rejected duplicate
            val afterDup = db.catalogDao().getRestaurant("rest_100")!!
            assertEquals(4.21, afterDup.rating, 0.001)
            assertEquals(101, afterDup.ratingCount)

            // Editing existing review from 5 -> 1 recalculates (420 + 5 - 5 + 1) / 101 = 421 / 101 = 4.17, count stays 101
            val edited = reviewRepository.updateReview("ORD-DUP", 1, "Updated feedback")
            assertTrue(edited is AppResult.Success)
            val editedSummary = (edited as AppResult.Success).data
            assertEquals(4.17, editedSummary.averageRating, 0.001)
            assertEquals(101, editedSummary.totalReviewCount)

            val afterEdit = db.catalogDao().getRestaurant("rest_100")!!
            assertEquals(4.17, afterEdit.rating, 0.001)
            assertEquals(101, afterEdit.ratingCount)
        }

    @Test
    fun `simultaneous reviews across multiple orders aggregate accurately without lost updates`() =
        runTest(testDispatcher) {
            seedDeliveredOrder(orderId = "ORD-C1", restaurantId = "rest_100")
            seedDeliveredOrder(orderId = "ORD-C2", restaurantId = "rest_100")
            seedDeliveredOrder(orderId = "ORD-C3", restaurantId = "rest_100")

            val results = listOf(
                async { reviewRepository.submitReview("ORD-C1", 5, "Great 1") },
                async { reviewRepository.submitReview("ORD-C2", 5, "Great 2") },
                async { reviewRepository.submitReview("ORD-C3", 4, "Good 3") }
            ).awaitAll()

            assertTrue(results.all { it is AppResult.Success })

            // Base sum = 4.2 * 100 = 420.0; +5 +5 +4 = 434.0 / 103 = 4.21359... -> 4.21
            val restaurant = db.catalogDao().getRestaurant("rest_100")!!
            assertEquals(103, restaurant.ratingCount)
            assertEquals(4.21, restaurant.rating, 0.001)
            assertEquals(3, reviewRepository.observeRestaurantReviews("rest_100").first().size)
        }

    @Test
    fun `zero-review restaurant formats as New and transitions accurately on first rating`() =
        runTest(testDispatcher) {
            assertEquals("New", RestaurantRatingCalculator.formatRating(0.0, 0))
            seedDeliveredOrder(orderId = "ORD-FIRST", restaurantId = "rest_zero")

            val res = reviewRepository.submitReview("ORD-FIRST", 4, "First diner!")
            assertTrue(res is AppResult.Success)
            val summary = (res as AppResult.Success).data
            assertEquals(4.0, summary.averageRating, 0.001)
            assertEquals(1, summary.totalReviewCount)
            assertEquals("4.0", RestaurantRatingCalculator.formatRating(summary.averageRating, summary.totalReviewCount))
        }

    @Test
    fun `offline or server error never corrupts local rating and subsequent retry succeeds`() =
        runTest(testDispatcher) {
            seedDeliveredOrder(orderId = "ORD-NET", restaurantId = "rest_100")

            // 1. Offline failure leaves Room aggregate untouched
            connectivity.setSimulatedOffline(true)
            val offlineRes = reviewRepository.submitReview("ORD-NET", 5, "Offline try")
            assertTrue(offlineRes is AppResult.Error)
            assertTrue((offlineRes as AppResult.Error).error is AppError.Offline)
            assertEquals(4.2, db.catalogDao().getRestaurant("rest_100")!!.rating, 0.001)
            assertEquals(100, db.catalogDao().getRestaurant("rest_100")!!.ratingCount)

            // 2. Server 500 failure leaves Room aggregate untouched
            connectivity.setSimulatedOffline(false)
            scenarioController.setNetworkFault(SimulatedNetworkFault.SERVER_ERROR_500)
            val serverErrRes = reviewRepository.submitReview("ORD-NET", 5, "Server 500 try")
            assertTrue(serverErrRes is AppResult.Error)
            assertEquals(4.2, db.catalogDao().getRestaurant("rest_100")!!.rating, 0.001)
            assertEquals(100, db.catalogDao().getRestaurant("rest_100")!!.ratingCount)

            // 3. Recovery retry succeeds cleanly
            scenarioController.setNetworkFault(SimulatedNetworkFault.NONE)
            val recovered = reviewRepository.submitReview("ORD-NET", 5, "Recovered!")
            assertTrue(recovered is AppResult.Success)
            assertEquals(4.21, db.catalogDao().getRestaurant("rest_100")!!.rating, 0.001)
            assertEquals(101, db.catalogDao().getRestaurant("rest_100")!!.ratingCount)
        }

    @Test
    fun `non-delivered order cannot be rated`() = runTest(testDispatcher) {
        db.orderDao().upsertOrder(
            OrderEntity(
                id = "ORD-ACTIVE",
                userId = "user_review_1",
                idempotencyKey = "idem_active",
                restaurantId = "rest_100",
                restaurantName = "Spice Route",
                restaurantAddress = "SG Hwy",
                restaurantLat = 23.02,
                restaurantLng = 72.57,
                deliveryAddress = "Home",
                deliveryLat = 23.03,
                deliveryLng = 72.53,
                deliveryInstructions = "",
                itemsJson = "[]",
                subtotal = 300.0,
                deliveryFee = 20.0,
                packagingCharge = 10.0,
                taxes = 15.0,
                discount = 0.0,
                totalPayable = 345.0,
                couponCode = null,
                orderStatus = OrderStatus.PREPARING.name,
                paymentStatus = "SUCCESS",
                paymentMethod = "UPI",
                deliveryPartnerId = null,
                estimatedDeliveryMinutes = 20,
                lastEventSequence = 2L,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                ratingGiven = null,
                reviewComment = null
            )
        )

        val res = reviewRepository.submitReview("ORD-ACTIVE", 5, "Too early")
        assertTrue(res is AppResult.Error)
        assertTrue((res as AppResult.Error).error is AppError.Validation)
    }

    private suspend fun seedDeliveredOrder(orderId: String, restaurantId: String) {
        db.orderDao().upsertOrder(
            OrderEntity(
                id = orderId,
                userId = "user_review_1",
                idempotencyKey = "idem_$orderId",
                restaurantId = restaurantId,
                restaurantName = "Spice Route",
                restaurantAddress = "SG Hwy",
                restaurantLat = 23.02,
                restaurantLng = 72.57,
                deliveryAddress = "Home",
                deliveryLat = 23.03,
                deliveryLng = 72.53,
                deliveryInstructions = "",
                itemsJson = "[]",
                subtotal = 300.0,
                deliveryFee = 20.0,
                packagingCharge = 10.0,
                taxes = 15.0,
                discount = 0.0,
                totalPayable = 345.0,
                couponCode = null,
                orderStatus = OrderStatus.DELIVERED.name,
                paymentStatus = "SUCCESS",
                paymentMethod = "UPI",
                deliveryPartnerId = null,
                estimatedDeliveryMinutes = 0,
                lastEventSequence = 7L,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                ratingGiven = null,
                reviewComment = null
            )
        )
    }

    private fun sampleRestaurant(id: String, rating: Double, ratingCount: Int) = RestaurantEntity(
        id = id,
        name = "Kitchen $id",
        cuisinesCsv = "North Indian",
        rating = rating,
        ratingCount = ratingCount,
        deliveryTimeMin = 25,
        deliveryFee = 20.0,
        minOrderAmount = 149.0,
        offerText = null,
        isPureVeg = false,
        isOpen = true,
        isPopular = true,
        isRecommended = true,
        imageKey = "indian",
        address = "SG Hwy",
        distanceKm = 2.0,
        latitude = 23.02,
        longitude = 72.57,
        lastUpdatedMs = 1L
    )
}
