package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.remote.dto.CartOperationRequestDto
import com.foodflow.data.repository.CartRepositoryImpl
import com.foodflow.data.sync.CartSyncWorker
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import com.foodflow.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CartSyncAndConflictResolutionTest {

    private lateinit var context: Context
    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var scenarioController: MockScenarioController
    private lateinit var mockBackend: LocalMockBackend
    private lateinit var workManager: WorkManager
    private lateinit var cartRepository: CartRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() = runTest(testDispatcher) {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        connectivity = FakeConnectivityObserver(initialOnline = false)
        scenarioController = MockScenarioController(connectivity).apply {
            setLatencyMs(0L)
            setOffline(true)
        }

        val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
        val userPrefs = UserPreferences(userId = "user_sync_1", authToken = "tok")
        every { prefs.preferences } returns flowOf(userPrefs)
        coEvery { prefs.getSnapshot() } returns userPrefs

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

        workManager = mockk(relaxed = true)

        cartRepository = CartRepositoryImpl(
            cartDao = db.cartDao(),
            pendingOperationDao = db.pendingOperationDao(),
            catalogDao = db.catalogDao(),
            favoriteDao = db.favoriteDao(),
            cartRemote = mockBackend,
            userRemote = mockBackend,
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            workManager = workManager,
            json = json,
            dispatchers = dispatchers
        )

        db.catalogDao().upsertRestaurants(
            listOf(
                RestaurantEntity(
                    id = "rest_1", name = "Spice Route", cuisinesCsv = "North Indian",
                    rating = 4.6, ratingCount = 400, deliveryTimeMin = 25, deliveryFee = 30.0,
                    minOrderAmount = 149.0, offerText = null, isPureVeg = false, isOpen = true,
                    isPopular = true, isRecommended = true, imageKey = "indian",
                    address = "SG Hwy", distanceKm = 2.0, latitude = 23.02, longitude = 72.57,
                    lastUpdatedMs = 1L
                ),
                RestaurantEntity(
                    id = "rest_2", name = "Dosa Express", cuisinesCsv = "South Indian",
                    rating = 4.5, ratingCount = 300, deliveryTimeMin = 20, deliveryFee = 25.0,
                    minOrderAmount = 99.0, offerText = null, isPureVeg = true, isOpen = true,
                    isPopular = false, isRecommended = true, imageKey = "south",
                    address = "Navrangpura", distanceKm = 1.5, latitude = 23.03, longitude = 72.56,
                    lastUpdatedMs = 1L
                )
            )
        )
        db.catalogDao().upsertFoodItems(
            listOf(
                FoodItemEntity(
                    id = "food_1", restaurantId = "rest_1", name = "Hyderabadi Dum Biryani",
                    description = "Rich biryani", categoryName = "Biryani", price = 280.0,
                    originalPrice = null, isVeg = false, isAvailable = true, isRecommended = true,
                    isBestseller = true, rating = 4.7, imageKey = "indian", addonsJson = "[]",
                    spiceLevel = "Medium"
                ),
                FoodItemEntity(
                    id = "food_2", restaurantId = "rest_2", name = "Mysore Masala Dosa",
                    description = "Crispy dosa", categoryName = "Dosa", price = 160.0,
                    originalPrice = null, isVeg = true, isAvailable = true, isRecommended = true,
                    isBestseller = true, rating = 4.6, imageKey = "south", addonsJson = "[]",
                    spiceLevel = "Mild"
                )
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `offline cart mutations persist locally queue outbox and trigger deferred WorkManager`() = runTest(testDispatcher) {
        val r1 = TestFixtures.restaurant(id = "rest_1", name = "Spice Route")
        val f1 = TestFixtures.foodItem(id = "food_1", restaurantId = "rest_1", price = 280.0)

        val addRes = cartRepository.addItem(r1, f1, addons = emptyList())
        assertTrue(addRes is AppResult.Success)

        val localItems = db.cartDao().getCartItems("user_sync_1")
        assertEquals(1, localItems.size)
        assertEquals("PENDING", localItems.first().syncStatus)

        val pendingOps = db.pendingOperationDao().getPendingOperations("user_sync_1")
        assertEquals(1, pendingOps.size)
        verify(atLeast = 1) { workManager.enqueueUniqueWork(CartSyncWorker.UNIQUE_WORK_NAME, any(), any<androidx.work.OneTimeWorkRequest>()) }
    }

    @Test
    fun `adding item from another restaurant returns typed RESTAURANT_MISMATCH conflict until cleared`() = runTest(testDispatcher) {
        val r1 = TestFixtures.restaurant(id = "rest_1", name = "Spice Route")
        val f1 = TestFixtures.foodItem(id = "food_1", restaurantId = "rest_1")
        val r2 = TestFixtures.restaurant(id = "rest_2", name = "Dosa Express")
        val f2 = TestFixtures.foodItem(id = "food_2", restaurantId = "rest_2", name = "Mysore Masala Dosa", price = 160.0)

        cartRepository.addItem(r1, f1, addons = emptyList())
        val conflict = cartRepository.addItem(r2, f2, addons = emptyList(), clearExistingDifferentRestaurant = false)
        assertTrue(conflict is AppResult.Error)
        assertTrue((conflict as AppResult.Error).error is AppError.Conflict)
        assertEquals("RESTAURANT_MISMATCH", conflict.error.technicalDetail)

        val replaced = cartRepository.addItem(r2, f2, addons = emptyList(), clearExistingDifferentRestaurant = true)
        assertTrue(replaced is AppResult.Success)
        val items = db.cartDao().getCartItems("user_sync_1")
        assertEquals(1, items.size)
        assertEquals("rest_2", items.first().restaurantId)
    }

    @Test
    fun `reconnecting reconciles server price change and unavailable item conflicts into Room`() = runTest(testDispatcher) {
        val r1 = TestFixtures.restaurant(id = "rest_1", name = "Spice Route")
        val f1 = TestFixtures.foodItem(id = "food_1", restaurantId = "rest_1", price = 280.0)
        cartRepository.addItem(r1, f1, addons = emptyList())

        // Simulate price change on server while user was offline (280 -> 320)
        scenarioController.overrideFoodPrice("food_1", 320.0)
        scenarioController.setOffline(false)

        val syncRes = cartRepository.syncPendingOperations()
        assertTrue(syncRes is AppResult.Success)
        val notices = (syncRes as AppResult.Success).data
        assertTrue(notices.any { it is CartReconciliationNotice.PriceChanged && it.newPrice == 320.0 })

        val updatedItem = db.cartDao().getCartItem("food_1", "user_sync_1")
        assertEquals(320.0, updatedItem?.unitPrice ?: 0.0, 0.001)
        assertEquals("SYNCED", updatedItem?.syncStatus)

        // Now queue another edit offline while server marks item out-of-stock -> sync removes item
        scenarioController.setOffline(true)
        cartRepository.updateQuantity("food_1", 2)
        scenarioController.toggleUnavailableFood("food_1")
        scenarioController.setOffline(false)

        val secondSync = cartRepository.syncPendingOperations()
        val notices2 = (secondSync as AppResult.Success).data
        assertTrue(notices2.any { it is CartReconciliationNotice.ItemUnavailable && it.itemId == "food_1" })
        assertEquals(0, db.cartDao().getCartItems("user_sync_1").size)
    }

    @Test
    fun `CartSyncWorker retries on transient server 500 and succeeds once backend recovers`() = runTest(testDispatcher) {
        val r1 = TestFixtures.restaurant(id = "rest_1", name = "Spice Route")
        val f1 = TestFixtures.foodItem(id = "food_1", restaurantId = "rest_1", price = 280.0)
        cartRepository.addItem(r1, f1, addons = emptyList())

        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = CartSyncWorker(appContext, workerParameters, cartRepository)
        }

        // Server 500 -> worker must return Result.retry()
        scenarioController.setOffline(false)
        scenarioController.setNetworkFault(SimulatedNetworkFault.SERVER_ERROR_500)
        val worker1 = TestListenableWorkerBuilder<CartSyncWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        assertEquals(ListenableWorker.Result.retry(), worker1.doWork())

        // Backend recovers -> worker returns Result.success() and drains outbox
        scenarioController.setNetworkFault(SimulatedNetworkFault.NONE)
        val worker2 = TestListenableWorkerBuilder<CartSyncWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        assertEquals(ListenableWorker.Result.success(), worker2.doWork())
        assertEquals(0, db.pendingOperationDao().getAllPendingOperations().size)
    }

    @Test
    fun `duplicate cart operationId replay is acknowledged idempotently without re-applying`() = runTest(testDispatcher) {
        scenarioController.setOffline(false)
        val req = CartOperationRequestDto(
            operationId = "op_dedup_99",
            userId = "user_sync_1",
            operationType = "UPSERT",
            itemId = "food_1",
            restaurantId = "rest_1",
            quantity = 1,
            expectedUnitPrice = 280.0
        )
        val firstAck = (mockBackend.syncOperation(req) as AppResult.Success).data
        val secondAck = (mockBackend.syncOperation(req) as AppResult.Success).data

        assertEquals(false, firstAck.duplicateIgnored)
        assertEquals(true, secondAck.duplicateIgnored)
    }

    @Test
    fun `cart preserves insertion order and isolates independent item quantities across all 8 mutation scenarios`() = runTest(testDispatcher) {
        val r1 = TestFixtures.restaurant(id = "rest_1", name = "Spice Route")
        val itemA = TestFixtures.foodItem(id = "food_a", restaurantId = "rest_1", name = "Item A Biryani", price = 280.0)
        val itemB = TestFixtures.foodItem(id = "food_b", restaurantId = "rest_1", name = "Item B Paneer", price = 240.0)
        val itemC = TestFixtures.foodItem(id = "food_c", restaurantId = "rest_1", name = "Item C Naan", price = 70.0)

        // Scenario 1: Add three distinct items -> exact insertion order [A, B, C]
        cartRepository.addItem(r1, itemA, addons = emptyList())
        cartRepository.addItem(r1, itemB, addons = emptyList())
        cartRepository.addItem(r1, itemC, addons = emptyList())
        val initial = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), initial.map { it.itemId })
        assertEquals(listOf(1, 1, 1), initial.map { it.quantity })

        // Scenario 2: Increase quantity of first item -> ONLY first item changes; third item untouched; order stays [A, B, C]
        cartRepository.updateQuantity("food_a", 2)
        val afterIncA = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), afterIncA.map { it.itemId })
        assertEquals(listOf(2, 1, 1), afterIncA.map { it.quantity })

        // Scenario 3: Decrease quantity of first item -> ONLY first item changes; order stays [A, B, C]
        cartRepository.updateQuantity("food_a", 1)
        val afterDecA = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), afterDecA.map { it.itemId })
        assertEquals(listOf(1, 1, 1), afterDecA.map { it.quantity })

        // Scenario 4: Increase quantity of second item -> order stays [A, B, C]
        cartRepository.updateQuantity("food_b", 3)
        val afterIncB = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), afterIncB.map { it.itemId })
        assertEquals(listOf(1, 3, 1), afterIncB.map { it.quantity })

        // Scenario 5: Increase quantity of third item -> order stays [A, B, C]
        cartRepository.updateQuantity("food_c", 4)
        val afterIncC = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), afterIncC.map { it.itemId })
        assertEquals(listOf(1, 3, 4), afterIncC.map { it.quantity })

        // Scenario 6: Rapidly tap + multiple times on first item -> deterministic count and stable order [A, B, C]
        for (q in 2..6) {
            cartRepository.updateQuantity("food_a", q)
        }
        val afterRapidA = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_b", "food_c"), afterRapidA.map { it.itemId })
        assertEquals(listOf(6, 3, 4), afterRapidA.map { it.quantity })

        // Scenario 7: Edit cooking instructions on item A and remove middle item B -> [A, C] with intact quantities
        cartRepository.updateCookingInstructions("food_a", "Extra spicy")
        cartRepository.removeItem("food_b")
        val afterRemoveB = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_c"), afterRemoveB.map { it.itemId })
        assertEquals(listOf(6, 4), afterRemoveB.map { it.quantity })
        assertEquals("Extra spicy", afterRemoveB.first().cookingInstructions)

        // Scenario 8: Re-read persisted cart state from Room -> exact order and quantities preserved
        val persisted = db.cartDao().getCartItems("user_sync_1")
        assertEquals(listOf("food_a", "food_c"), persisted.map { it.itemId })
        assertEquals(listOf(6, 4), persisted.map { it.quantity })
        assertTrue(persisted[0].addedAtMs < persisted[1].addedAtMs)
    }
}
