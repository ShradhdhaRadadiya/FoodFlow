package com.foodflow.domain

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.scheduled.MockFoodItemAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockLatestPricingDataSourceImpl
import com.foodflow.data.mock.scheduled.MockRestaurantAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledOrderExecutionDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledPaymentValidationDataSourceImpl
import com.foodflow.data.repository.ScheduledOrderRepositoryImpl
import com.foodflow.data.sync.ScheduledOrderWorkScheduler
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.ScheduleFailureCategory
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleRecurrenceType
import com.foodflow.domain.model.ScheduledFailureAction
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderStatus
import com.foodflow.domain.repository.OrderTrackingRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.scheduled.BuildScheduledFailurePopupUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.feature.orders.ScheduledOrderHistoryCard
import com.foodflow.feature.scheduled.ScheduledOrderFailureDialog
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ScheduleOrderPlacementAndFailureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val formatPlacement = FormatScheduledPlacementUseCase()
    private val buildFailurePopup = BuildScheduledFailurePopupUseCase()

    @Test
    fun `FormatScheduledPlacementUseCase formats exact order placement confirmation sentence`() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sentence = formatPlacement.formatConfirmationSentence(
            placementUtcMs = cal.timeInMillis,
            timezoneId = "Asia/Kolkata"
        )
        assertEquals("Your order will be placed on 20 November at 8:00 PM.", sentence)
    }

    @Test
    fun `BuildScheduledFailurePopupUseCase produces exact copy and actions for restaurant closed and unavailable items`() {
        val closedSchedule = sampleScheduledOrder(
            status = ScheduledOrderStatus.FAILED,
            failureCategory = ScheduleFailureCategory.RESTAURANT_CLOSED,
            failureReason = "Restaurant is closed during the requested slot."
        )
        val closedPopup = buildFailurePopup(closedSchedule)
        assertEquals("Unable to Place Your Scheduled Order", closedPopup.title)
        assertEquals(
            "Unfortunately, the restaurant is not accepting orders at your scheduled time. Please select another time or restaurant.",
            closedPopup.message
        )
        assertEquals(ScheduledFailureAction.RESCHEDULE, closedPopup.primaryAction)
        assertEquals(ScheduledFailureAction.CANCEL_ORDER, closedPopup.secondaryAction)

        val unavailableSchedule = sampleScheduledOrder(
            status = ScheduledOrderStatus.FAILED,
            failureCategory = ScheduleFailureCategory.ITEM_UNAVAILABLE,
            failureReason = "Unavailable item(s): Paneer Tikka, Garlic Naan. Order was not placed."
        )
        val unavailablePopup = buildFailurePopup(unavailableSchedule)
        assertEquals("Items Unavailable", unavailablePopup.title)
        assertEquals(
            "Some items in your scheduled order are no longer available. Please review your cart and update your order.",
            unavailablePopup.message
        )
        assertEquals(listOf("Paneer Tikka", "Garlic Naan"), unavailablePopup.unavailableItems)
        assertEquals(ScheduledFailureAction.REVIEW_ORDER, unavailablePopup.primaryAction)
        assertEquals(ScheduledFailureAction.CANCEL_ORDER, unavailablePopup.secondaryAction)
    }

    @Test
    fun `ScheduledOrderRepositoryImpl prevents duplicate creation, blocks edits during PLACING_ORDER, and acknowledges failure popups`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(testDispatcher)
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            try {
                val connectivity = FakeConnectivityObserver(initialOnline = true)
                val scenarioController = MockScenarioController(connectivity).apply { setLatencyMs(0L) }
                val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
                val userPrefs = UserPreferences(userId = "user_guard_1", authToken = "tok_1")
                every { prefs.preferences } returns flowOf(userPrefs)
                coEvery { prefs.getSnapshot() } returns userPrefs

                val seeder = mockk<DatabaseSeeder>(relaxed = true)
                coEvery { seeder.loadSeedDataset() } returns null

                val mockBackend = LocalMockBackend(
                    connectivityObserver = connectivity,
                    scenarioController = scenarioController,
                    seeder = seeder,
                    catalogDao = db.catalogDao(),
                    serverStateDao = db.mockServerStateDao(),
                    json = json
                )

                val repo = ScheduledOrderRepositoryImpl(
                    scheduledOrderDao = db.scheduledOrderDao(),
                    cartDao = db.cartDao(),
                    catalogDao = db.catalogDao(),
                    addressDao = db.addressDao(),
                    orderDao = db.orderDao(),
                    restaurantAvailabilityDataSource = MockRestaurantAvailabilityDataSourceImpl(
                        db.catalogDao(), db.addressDao(), scenarioController, connectivity
                    ),
                    foodItemAvailabilityDataSource = MockFoodItemAvailabilityDataSourceImpl(
                        db.catalogDao(), scenarioController, connectivity
                    ),
                    latestPricingDataSource = MockLatestPricingDataSourceImpl(
                        db.catalogDao(), CalculateCartTotalsUseCase(), scenarioController, connectivity
                    ),
                    paymentValidationDataSource = MockScheduledPaymentValidationDataSourceImpl(
                        scenarioController, connectivity
                    ),
                    executionDataSource = MockScheduledOrderExecutionDataSourceImpl(mockBackend),
                    orderTrackingRepository = mockk<OrderTrackingRepository>(relaxed = true),
                    calculateNextOccurrence = CalculateNextScheduleOccurrenceUseCase(),
                    workScheduler = object : ScheduledOrderWorkScheduler {
                        override fun scheduleExecution(
                            scheduleId: String,
                            targetExecutionAtUtcMs: Long,
                            expectedOccurrenceNumber: Int
                        ) = Unit

                        override fun cancel(scheduleId: String) = Unit
                    },
                    connectivityObserver = connectivity,
                    preferencesDataStore = prefs,
                    json = json,
                    dispatchers = dispatchers
                )

                db.catalogDao().upsertRestaurants(
                    listOf(
                        RestaurantEntity(
                            id = "rest_1", name = "Spice Route", cuisinesCsv = "North Indian",
                            rating = 4.6, ratingCount = 200, deliveryTimeMin = 25, deliveryFee = 30.0,
                            minOrderAmount = 99.0, offerText = null, isPureVeg = false, isOpen = true,
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
                            description = "Smoky tikka", categoryName = "Starters",
                            price = 280.0, originalPrice = null, isVeg = true, isAvailable = true,
                            isRecommended = true, isBestseller = true, rating = 4.7,
                            imageKey = "indian", addonsJson = "[]", spiceLevel = "MEDIUM"
                        )
                    )
                )
                db.addressDao().upsertAddress(
                    AddressEntity(
                        id = "addr_1", userId = "user_guard_1", label = "HOME",
                        recipientName = "Aarav", line1 = "14 Satellite", landmark = "",
                        city = "Ahmedabad", pincode = "380015", deliveryInstructions = "",
                        latitude = 23.03, longitude = 72.53, isDefault = true, isServiceable = true
                    )
                )
                db.cartDao().upsertCartItem(
                    CartItemEntity(
                        itemId = "food_1", userId = "user_guard_1", restaurantId = "rest_1",
                        restaurantName = "Spice Route", foodName = "Paneer Tikka", unitPrice = 280.0,
                        quantity = 1, isVeg = true, imageKey = "indian", selectedAddonsJson = "[]",
                        cookingInstructions = "", syncStatus = "SYNCED", updatedAtMs = 1L
                    )
                )

                val slotCal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 20)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val params = CreateScheduledOrderParams(
                    restaurantId = "rest_1",
                    addressId = "addr_1",
                    paymentMethod = PaymentMethod.UPI,
                    couponCode = null,
                    scheduledAtUtcMs = slotCal.timeInMillis,
                    recurrenceRule = ScheduleRecurrenceRule(
                        type = ScheduleRecurrenceType.ONE_TIME,
                        hourOfDay = 20,
                        minute = 0,
                        timezoneId = TimeZone.getDefault().id
                    ),
                    priceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE,
                    creationIdempotencyKey = "idem_create_slot_20_00"
                )

                val firstCreated = (repo.createScheduledOrder(params) as AppResult.Success).data
                // Duplicate submission with same creationIdempotencyKey must return the same schedule without duplicating
                val duplicateAttempt = (repo.createScheduledOrder(params) as AppResult.Success).data
                assertEquals(firstCreated.id, duplicateAttempt.id)
                assertEquals(1, repo.observeScheduledOrders().first().size)

                // Simulate in-flight PLACING_ORDER lock -> cancel & reschedule MUST be rejected
                val placingEntity = db.scheduledOrderDao().getScheduledOrder(firstCreated.id)!!
                    .copy(status = ScheduledOrderStatus.PLACING_ORDER.name)
                db.scheduledOrderDao().upsertScheduledOrder(placingEntity)
                assertTrue(repo.cancelScheduledOrder(firstCreated.id, "Changed mind") is AppResult.Error)
                assertTrue(
                    repo.updateScheduledOrder(
                        firstCreated.id,
                        slotCal.timeInMillis + 3_600_000L,
                        params.recurrenceRule
                    ) is AppResult.Error
                )

                // Restore UPCOMING and fail on unavailable item -> persisted FAILED & unacknowledged popup alert emitted
                db.scheduledOrderDao().upsertScheduledOrder(
                    placingEntity.copy(status = ScheduledOrderStatus.UPCOMING.name)
                )
                scenarioController.toggleUnavailableFood("food_1")
                repo.executeDueOccurrence(firstCreated.id, 1, forceRunNowForDemo = true)
                scenarioController.toggleUnavailableFood("food_1")

                val unacknowledged = repo.observeUnacknowledgedFailedSchedules().first()
                assertEquals(1, unacknowledged.size)
                assertEquals(ScheduledOrderStatus.FAILED, unacknowledged.first().status)
                assertFalse(unacknowledged.first().failureAcknowledged)

                // Acknowledge popup -> alert clears while persisted FAILED status remains in history
                repo.acknowledgeScheduledOrderFailure(firstCreated.id)
                assertTrue(repo.observeUnacknowledgedFailedSchedules().first().isEmpty())
                val persistedFailed = repo.observeScheduledOrder(firstCreated.id).first()!!
                assertEquals(ScheduledOrderStatus.FAILED, persistedFailed.status)
                assertTrue(persistedFailed.failureAcknowledged)
            } finally {
                db.close()
            }
        }

    @Test
    fun `ScheduledOrderHistoryCard hides Track Order before placement and shows it only after order is placed`() {
        val upcomingSchedule = sampleScheduledOrder(
            id = "SCH-UPCOMING-1",
            status = ScheduledOrderStatus.UPCOMING,
            lastGeneratedOrderId = null
        )

        composeRule.setContent {
            FoodFlowTheme {
                ScheduledOrderHistoryCard(
                    schedule = upcomingSchedule,
                    imageKey = "indian",
                    placementSentence = "Your order will be placed on 20 November at 8:00 PM.",
                    recurrenceSummary = "One-time",
                    isExecuting = false,
                    onReschedule = {},
                    onCancel = {},
                    onViewDetails = {},
                    onPlaceNowDemo = {},
                    onTrackOrder = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Your order will be placed on 20 November at 8:00 PM.").assertIsDisplayed()
        composeRule.onNodeWithTag("orders_place_now_schedule_SCH-UPCOMING-1").assertIsDisplayed()
        composeRule.onNodeWithTag("orders_reschedule_schedule_SCH-UPCOMING-1").assertIsDisplayed()
        // Never allow premature tracking before actual order placement
        composeRule.onNodeWithTag("orders_track_generated_SCH-UPCOMING-1").assertDoesNotExist()
    }

    @Test
    fun `ScheduledOrderHistoryCard exposes Track Order once order placement has succeeded`() {
        var trackedId: String? = null
        val placedSchedule = sampleScheduledOrder(
            id = "SCH-PLACED-2",
            status = ScheduledOrderStatus.COMPLETED,
            lastGeneratedOrderId = "FF-2026-8888"
        )

        composeRule.setContent {
            FoodFlowTheme {
                ScheduledOrderHistoryCard(
                    schedule = placedSchedule,
                    imageKey = "indian",
                    placementSentence = "Your order will be placed on 20 November at 8:00 PM.",
                    recurrenceSummary = "One-time",
                    isExecuting = false,
                    onReschedule = {},
                    onCancel = {},
                    onViewDetails = {},
                    onPlaceNowDemo = {},
                    onTrackOrder = { trackedId = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("orders_track_generated_SCH-PLACED-2").assertIsDisplayed().performClick()
        assertEquals("FF-2026-8888", trackedId)
    }

    @Test
    fun `ScheduledOrderFailureDialog displays unavailable items and fires Review Order and Cancel Order callbacks`() {
        var primaryClicks = 0
        var cancelClicks = 0
        val schedule = sampleScheduledOrder(
            status = ScheduledOrderStatus.FAILED,
            failureCategory = ScheduleFailureCategory.ITEM_UNAVAILABLE,
            failureReason = "Unavailable item(s): Paneer Tikka, Dal Makhani. Order was not placed."
        )
        val popup = buildFailurePopup(schedule)

        composeRule.setContent {
            FoodFlowTheme {
                ScheduledOrderFailureDialog(
                    popup = popup,
                    onPrimaryAction = { primaryClicks++ },
                    onCancelOrder = { cancelClicks++ },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("scheduled_failure_popup_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Items Unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Paneer Tikka").assertIsDisplayed()
        composeRule.onNodeWithText("Dal Makhani").assertIsDisplayed()

        composeRule.onNodeWithTag("scheduled_failure_primary_action").performClick()
        composeRule.onNodeWithTag("scheduled_failure_cancel_order_action").performClick()
        assertEquals(1, primaryClicks)
        assertEquals(1, cancelClicks)
    }

    private fun sampleScheduledOrder(
        id: String = "SCH-TEST-1",
        status: ScheduledOrderStatus = ScheduledOrderStatus.UPCOMING,
        failureCategory: ScheduleFailureCategory = ScheduleFailureCategory.NONE,
        failureReason: String? = null,
        lastGeneratedOrderId: String? = null
    ) = ScheduledOrder(
        id = id,
        userId = "u1",
        restaurantId = "rest_1",
        restaurantName = "Spice Route Kitchen",
        restaurantAddress = "SG Highway, Ahmedabad",
        restaurantLat = 23.0225,
        restaurantLng = 72.5714,
        addressId = "addr_1",
        deliveryAddress = "14 Satellite, Ahmedabad",
        deliveryLat = 23.0300,
        deliveryLng = 72.5300,
        deliveryInstructions = "Leave at door",
        items = listOf(
            OrderItem(
                foodId = "food_1",
                foodName = "Paneer Tikka",
                quantity = 2,
                unitPrice = 280.0,
                isVeg = true,
                addonsSummary = ""
            )
        ),
        authorizedSubtotal = 560.0,
        authorizedDeliveryFee = 30.0,
        authorizedPackagingCharge = 12.0,
        authorizedTaxes = 18.0,
        authorizedDiscount = 0.0,
        authorizedTotalPayable = 620.0,
        couponCode = null,
        paymentMethod = PaymentMethod.UPI,
        priceConsentPolicy = SchedulePriceConsentPolicy.STRICT_NO_INCREASE,
        recurrenceRule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.ONE_TIME,
            hourOfDay = 20,
            minute = 0,
            timezoneId = "Asia/Kolkata"
        ),
        nextExecutionAtUtcMs = 1_795_185_000_000L,
        status = status,
        completedOccurrences = if (status == ScheduledOrderStatus.COMPLETED) 1 else 0,
        lastExecutedAtUtcMs = null,
        lastGeneratedOrderId = lastGeneratedOrderId,
        lastFailureCategory = failureCategory,
        lastFailureReason = failureReason,
        failureAcknowledged = false,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L
    )
}
