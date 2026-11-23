package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedPaymentOutcome
import com.foodflow.data.mock.scheduled.MockFoodItemAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockLatestPricingDataSourceImpl
import com.foodflow.data.mock.scheduled.MockRestaurantAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledOrderExecutionDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledPaymentValidationDataSourceImpl
import com.foodflow.data.repository.ScheduledOrderRepositoryImpl
import com.foodflow.data.sync.ScheduledOrderWorkScheduler
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.model.ScheduleExecutionStatus
import com.foodflow.domain.model.ScheduleFailureCategory
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleRecurrenceType
import com.foodflow.domain.model.ScheduledOrderStatus
import com.foodflow.domain.repository.OrderTrackingRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ScheduledOrderLifecycleAndExecutionTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var scenarioController: MockScenarioController
    private lateinit var mockBackend: LocalMockBackend
    private lateinit var restaurantAvailability: MockRestaurantAvailabilityDataSourceImpl
    private lateinit var foodAvailability: MockFoodItemAvailabilityDataSourceImpl
    private lateinit var latestPricing: MockLatestPricingDataSourceImpl
    private lateinit var paymentValidation: MockScheduledPaymentValidationDataSourceImpl
    private lateinit var executionDataSource: MockScheduledOrderExecutionDataSourceImpl
    private lateinit var trackingRepo: OrderTrackingRepository
    private lateinit var fakeWorkScheduler: RecordingWorkScheduler
    private lateinit var repository: ScheduledOrderRepositoryImpl

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
        val defaultPrefs = UserPreferences(userId = "user_sched_1", authToken = "tok_test")
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

        restaurantAvailability = MockRestaurantAvailabilityDataSourceImpl(
            catalogDao = db.catalogDao(),
            addressDao = db.addressDao(),
            scenarioController = scenarioController,
            connectivityObserver = connectivity
        )
        foodAvailability = MockFoodItemAvailabilityDataSourceImpl(
            catalogDao = db.catalogDao(),
            scenarioController = scenarioController,
            connectivityObserver = connectivity
        )
        latestPricing = MockLatestPricingDataSourceImpl(
            catalogDao = db.catalogDao(),
            calculateCartTotals = CalculateCartTotalsUseCase(),
            scenarioController = scenarioController,
            connectivityObserver = connectivity
        )
        paymentValidation = MockScheduledPaymentValidationDataSourceImpl(
            scenarioController = scenarioController,
            connectivityObserver = connectivity
        )
        executionDataSource = MockScheduledOrderExecutionDataSourceImpl(
            orderRemote = mockBackend
        )
        trackingRepo = mockk(relaxed = true)
        fakeWorkScheduler = RecordingWorkScheduler()

        repository = ScheduledOrderRepositoryImpl(
            scheduledOrderDao = db.scheduledOrderDao(),
            cartDao = db.cartDao(),
            catalogDao = db.catalogDao(),
            addressDao = db.addressDao(),
            orderDao = db.orderDao(),
            restaurantAvailabilityDataSource = restaurantAvailability,
            foodItemAvailabilityDataSource = foodAvailability,
            latestPricingDataSource = latestPricing,
            paymentValidationDataSource = paymentValidation,
            executionDataSource = executionDataSource,
            orderTrackingRepository = trackingRepo,
            calculateNextOccurrence = CalculateNextScheduleOccurrenceUseCase(),
            workScheduler = fakeWorkScheduler,
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            json = json,
            dispatchers = dispatchers
        )

        seedBaseCatalogAndCart()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `one-time schedule creates order on execution, starts real-time tracking, and marks COMPLETED`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 13, minute = 0)
            val created = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data

            assertEquals(ScheduledOrderStatus.UPCOMING, created.status)
            assertEquals(1, fakeWorkScheduler.scheduledCalls.size)
            // Cart cleared after scheduling
            assertTrue(db.cartDao().getCartItems("user_sched_1").isEmpty())

            // Execute occurrence when due (demo trigger simulates reaching due timestamp)
            val execRes = repository.executeDueOccurrence(created.id, expectedOccurrenceNumber = 1, forceRunNowForDemo = true)
            assertTrue(execRes is AppResult.Success)
            val outcome = (execRes as AppResult.Success).data
            assertTrue(outcome is ScheduleExecutionOutcome.Succeeded)
            val succeeded = outcome as ScheduleExecutionOutcome.Succeeded

            // Real order created in Room orders table
            val createdOrder = db.orderDao().getOrder(succeeded.generatedOrder.id)
            assertNotNull(createdOrder)
            assertEquals("rest_1", createdOrder?.restaurantId)

            // Real-time tracking workflow started for the newly generated order
            verify(exactly = 1) { trackingRepo.startTracking(succeeded.generatedOrder.id) }

            // Schedule marked COMPLETED & execution history recorded
            val updatedSchedule = repository.observeScheduledOrder(created.id).first()!!
            assertEquals(ScheduledOrderStatus.COMPLETED, updatedSchedule.status)
            assertEquals(1, updatedSchedule.completedOccurrences)
            assertEquals(succeeded.generatedOrder.id, updatedSchedule.lastGeneratedOrderId)

            val history = repository.observeExecutionHistory(created.id).first()
            assertEquals(1, history.size)
            assertEquals(ScheduleExecutionStatus.SUCCEEDED, history.first().status)
        }

    @Test
    fun `recurring schedule chains next occurrence via WorkManager and stops after max occurrences`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 19, minute = 0)
            val rule = ScheduleRecurrenceRule(
                type = ScheduleRecurrenceType.DAILY,
                hourOfDay = 19,
                minute = 0,
                endConditionType = ScheduleEndConditionType.AFTER_OCCURRENCES,
                maxOccurrences = 2,
                timezoneId = TimeZone.getDefault().id
            )
            val created = (
                repository.createScheduledOrder(
                    CreateScheduledOrderParams(
                        restaurantId = "rest_1",
                        addressId = "addr_1",
                        paymentMethod = PaymentMethod.UPI,
                        couponCode = null,
                        scheduledAtUtcMs = slot,
                        recurrenceRule = rule
                    )
                ) as AppResult.Success
            ).data

            // Occurrence #1 -> stays UPCOMING and chains occurrence #2
            val run1 = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(run1 is ScheduleExecutionOutcome.Succeeded)
            val afterRun1 = repository.observeScheduledOrder(created.id).first()!!
            assertEquals(ScheduledOrderStatus.UPCOMING, afterRun1.status)
            assertEquals(1, afterRun1.completedOccurrences)
            assertTrue(afterRun1.nextExecutionAtUtcMs > created.nextExecutionAtUtcMs)
            assertEquals(2, fakeWorkScheduler.scheduledCalls.last().second) // occurrence #2 chained

            // Occurrence #2 -> completes schedule
            val run2 = (repository.executeDueOccurrence(created.id, 2, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(run2 is ScheduleExecutionOutcome.Succeeded)
            val afterRun2 = repository.observeScheduledOrder(created.id).first()!!
            assertEquals(ScheduledOrderStatus.COMPLETED, afterRun2.status)
            assertEquals(2, afterRun2.completedOccurrences)
            assertEquals(2, repository.observeExecutionHistory(created.id).first().size)
        }

    @Test
    fun `duplicate concurrent execution triggers for same occurrence create only ONE order`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 14, minute = 0)
            val created = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data

            val outcomes = listOf(
                async { repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) },
                async { repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) }
            ).awaitAll()

            val succeededCount = outcomes.count { (it as? AppResult.Success)?.data is ScheduleExecutionOutcome.Succeeded }
            val skippedCount = outcomes.count { (it as? AppResult.Success)?.data is ScheduleExecutionOutcome.Skipped }
            assertEquals(1, succeededCount)
            assertEquals(1, skippedCount)
            assertEquals(1, db.orderDao().observeOrders("user_sched_1").first().size)
        }

    @Test
    fun `pause, resume, reschedule, and cancel lifecycle prevent execution when inactive`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 15, minute = 0)
            val created = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data

            // Pause cancels WorkManager & blocks execution
            val paused = (repository.pauseScheduledOrder(created.id) as AppResult.Success).data
            assertEquals(ScheduledOrderStatus.PAUSED, paused.status)
            assertTrue(created.id in fakeWorkScheduler.cancelledIds)

            val skippedWhilePaused = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(skippedWhilePaused is ScheduleExecutionOutcome.Skipped)

            // Resume restores UPCOMING
            val resumed = (repository.resumeScheduledOrder(created.id) as AppResult.Success).data
            assertEquals(ScheduledOrderStatus.UPCOMING, resumed.status)

            // Reschedule updates target timestamp
            val newSlot = futureValidSlotMs(daysAhead = 2, hour = 18, minute = 30)
            val rescheduled = (
                repository.updateScheduledOrder(
                    scheduleId = created.id,
                    newScheduledAtUtcMs = newSlot,
                    newRecurrenceRule = created.recurrenceRule.copy(hourOfDay = 18, minute = 30)
                ) as AppResult.Success
            ).data
            assertEquals(newSlot, rescheduled.nextExecutionAtUtcMs)

            // Cancel permanently blocks execution
            val cancelled = (repository.cancelScheduledOrder(created.id, "Plans changed") as AppResult.Success).data
            assertEquals(ScheduledOrderStatus.CANCELLED, cancelled.status)
            val skippedAfterCancel = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(skippedAfterCancel is ScheduleExecutionOutcome.Skipped)
        }

    @Test
    fun `price increase under STRICT_NO_INCREASE blocks silent higher charge while price drop succeeds`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 16, minute = 0)
            val created = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data

            // Menu item price increases from 300 -> 390
            scenarioController.overrideFoodPrice("food_1", 390.0)
            val failedRes = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(failedRes is ScheduleExecutionOutcome.Failed)
            val failed = failedRes as ScheduleExecutionOutcome.Failed
            assertEquals(ScheduleFailureCategory.PRICE_CHANGED_REQUIRES_CONSENT, failed.execution.failureCategory)
            assertEquals(0, db.orderDao().observeOrders("user_sched_1").first().size)

            // Resume after price drops to 260 (below authorized) -> executes cleanly at lower live price
            scenarioController.overrideFoodPrice("food_1", 260.0)
            repository.resumeScheduledOrder(created.id)
            val successRes = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = true) as AppResult.Success).data
            assertTrue(successRes is ScheduleExecutionOutcome.Succeeded)
            val generated = (successRes as ScheduleExecutionOutcome.Succeeded).generatedOrder
            assertTrue(generated.totalPayable < created.authorizedTotalPayable)
            scenarioController.overrideFoodPrice("food_1", null)
        }

    @Test
    fun `restaurant paused, item unavailable, and payment declined fail gracefully without creating orders`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 17, minute = 0)

            // Case 1: Restaurant paused
            val s1 = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data
            scenarioController.setRestaurantRejectsOrder(true)
            val r1 = (repository.executeDueOccurrence(s1.id, 1, forceRunNowForDemo = true) as AppResult.Success).data as ScheduleExecutionOutcome.Failed
            assertEquals(ScheduleFailureCategory.RESTAURANT_PAUSED, r1.execution.failureCategory)
            scenarioController.setRestaurantRejectsOrder(false)

            // Case 2: Item unavailable
            seedCartOnly()
            val s2 = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data
            scenarioController.toggleUnavailableFood("food_1")
            val r2 = (repository.executeDueOccurrence(s2.id, 1, forceRunNowForDemo = true) as AppResult.Success).data as ScheduleExecutionOutcome.Failed
            assertEquals(ScheduleFailureCategory.ITEM_UNAVAILABLE, r2.execution.failureCategory)
            scenarioController.toggleUnavailableFood("food_1")

            // Case 3: Payment declined
            seedCartOnly()
            val s3 = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data
            scenarioController.setPaymentOutcome(SimulatedPaymentOutcome.FAILED)
            val r3 = (repository.executeDueOccurrence(s3.id, 1, forceRunNowForDemo = true) as AppResult.Success).data as ScheduleExecutionOutcome.Failed
            assertEquals(ScheduleFailureCategory.PAYMENT_FAILED, r3.execution.failureCategory)

            assertEquals(0, db.orderDao().observeOrders("user_sched_1").first().size)
        }

    @Test
    fun `offline execution queues retry and overdue reconciliation executes after app restart`() =
        runTest(testDispatcher) {
            val slot = futureValidSlotMs(daysAhead = 1, hour = 13, minute = 30)
            val created = (repository.createScheduledOrder(oneTimeParams(slot)) as AppResult.Success).data

            // Simulate schedule became overdue while app was killed/offline
            val overdueEntity = db.scheduledOrderDao().getScheduledOrder(created.id)!!.copy(
                nextExecutionAtUtcMs = System.currentTimeMillis() - 60_000L
            )
            db.scheduledOrderDao().upsertScheduledOrder(overdueEntity)

            // Offline -> retryable failure, stays UPCOMING
            connectivity.setSimulatedOffline(true)
            val offlineAttempt = (repository.executeDueOccurrence(created.id, 1, forceRunNowForDemo = false) as AppResult.Success).data as ScheduleExecutionOutcome.Failed
            assertTrue(offlineAttempt.shouldRetry)
            assertEquals(ScheduleFailureCategory.NETWORK_ERROR, offlineAttempt.execution.failureCategory)
            assertEquals(ScheduledOrderStatus.UPCOMING, repository.observeScheduledOrder(created.id).first()!!.status)

            // Reconnect & reconcile overdue schedules -> executes cleanly!
            connectivity.setSimulatedOffline(false)
            val reconciled = repository.reconcileOverdueSchedules()
            assertEquals(1, reconciled)
            assertEquals(ScheduledOrderStatus.COMPLETED, repository.observeScheduledOrder(created.id).first()!!.status)
            assertEquals(1, db.orderDao().observeOrders("user_sched_1").first().size)
        }

    private fun oneTimeParams(slotUtcMs: Long): CreateScheduledOrderParams {
        val cal = Calendar.getInstance().apply { timeInMillis = slotUtcMs }
        return CreateScheduledOrderParams(
            restaurantId = "rest_1",
            addressId = "addr_1",
            paymentMethod = PaymentMethod.UPI,
            couponCode = null,
            scheduledAtUtcMs = slotUtcMs,
            recurrenceRule = ScheduleRecurrenceRule(
                type = ScheduleRecurrenceType.ONE_TIME,
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                timezoneId = TimeZone.getDefault().id
            ),
            priceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE
        )
    }

    private fun futureValidSlotMs(daysAhead: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private suspend fun seedBaseCatalogAndCart() {
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
        db.catalogDao().upsertFoodItems(
            listOf(
                FoodItemEntity(
                    id = "food_1", restaurantId = "rest_1", name = "Paneer Tikka",
                    description = "Chargrilled cottage cheese", categoryName = "Starters",
                    price = 300.0, originalPrice = null, isVeg = true, isAvailable = true,
                    isRecommended = true, isBestseller = true, rating = 4.7,
                    imageKey = "indian", addonsJson = "[]", spiceLevel = "MEDIUM"
                )
            )
        )
        db.addressDao().upsertAddress(
            AddressEntity(
                id = "addr_1", userId = "user_sched_1", label = "HOME", recipientName = "Aarav",
                line1 = "14 Satellite", landmark = "Near Mall", city = "Ahmedabad",
                pincode = "380015", deliveryInstructions = "Leave at door", latitude = 23.03,
                longitude = 72.53, isDefault = true, isServiceable = true
            )
        )
        seedCartOnly()
    }

    private suspend fun seedCartOnly() {
        db.cartDao().upsertCartItem(
            CartItemEntity(
                itemId = "food_1", userId = "user_sched_1", restaurantId = "rest_1",
                restaurantName = "Spice Route", foodName = "Paneer Tikka", unitPrice = 300.0,
                quantity = 1, isVeg = true, imageKey = "indian", selectedAddonsJson = "[]",
                cookingInstructions = "", syncStatus = "SYNCED", updatedAtMs = 1L
            )
        )
    }

    private class RecordingWorkScheduler : ScheduledOrderWorkScheduler {
        val scheduledCalls = mutableListOf<Pair<String, Int>>()
        val cancelledIds = mutableListOf<String>()

        override fun scheduleExecution(
            scheduleId: String,
            targetExecutionAtUtcMs: Long,
            expectedOccurrenceNumber: Int
        ) {
            scheduledCalls += scheduleId to expectedOccurrenceNumber
        }

        override fun cancel(scheduleId: String) {
            cancelledIds += scheduleId
        }
    }
}
