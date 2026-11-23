package com.foodflow.feature

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.local.paging.LocalCatalogPagingDataSource
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.model.RestaurantSortOption
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.RestaurantRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.ValidateCouponUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ClearCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.SyncPendingCartOperationsUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.cart.UpdateCookingInstructionsUseCase
import com.foodflow.domain.usecase.cart.ValidateCartUseCase
import com.foodflow.domain.usecase.restaurant.GetCouponByCodeUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCouponsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.feature.cart.CartIntent
import com.foodflow.feature.cart.CartViewModel
import com.foodflow.feature.splash.SplashStoryStage
import com.foodflow.feature.splash.SplashStoryTimeline
import com.foodflow.feature.voice.VoiceAssistantBottomSheet
import com.foodflow.feature.voice.VoiceAssistantIntent
import com.foodflow.feature.voice.VoiceAssistantUiState
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import com.foodflow.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SplashPaginationCartVoiceImprovementsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `SplashStoryTimeline progresses cleanly across all 5 connected delivery stages with reduced motion option`() {
        assertEquals(SplashStoryStage.KITCHEN_COOKING, SplashStoryTimeline.stageForProgress(0.0f))
        assertEquals(SplashStoryStage.KITCHEN_COOKING, SplashStoryTimeline.stageForProgress(0.18f))
        assertEquals(SplashStoryStage.PARTNER_PICKUP, SplashStoryTimeline.stageForProgress(0.30f))
        assertEquals(SplashStoryStage.ROAD_DELIVERY, SplashStoryTimeline.stageForProgress(0.55f))
        assertEquals(SplashStoryStage.DOORSTEP_HANDOVER, SplashStoryTimeline.stageForProgress(0.78f))
        assertEquals(SplashStoryStage.BRAND_FINALE, SplashStoryTimeline.stageForProgress(0.92f))
        assertEquals(SplashStoryStage.BRAND_FINALE, SplashStoryTimeline.stageForProgress(1.0f))

        assertEquals(0f, SplashStoryTimeline.localStageProgress(0.0f, SplashStoryStage.ROAD_DELIVERY), 0.001f)
        assertEquals(1f, SplashStoryTimeline.localStageProgress(0.95f, SplashStoryStage.ROAD_DELIVERY), 0.001f)
        assertTrue(SplashStoryTimeline.REDUCED_MOTION_DURATION_MS < SplashStoryTimeline.TOTAL_STORY_DURATION_MS)
        assertEquals(3_000L, SplashStoryTimeline.TOTAL_STORY_DURATION_MS)
    }

    @Test
    fun `LocalCatalogPagingDataSource paginates restaurants and menu deterministically without duplicates and respects filters`() =
        runTest(testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val dispatchers = TestDispatcherProvider(testDispatcher)
                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                val dataSource = LocalCatalogPagingDataSource(
                    catalogDao = db.catalogDao(),
                    favoriteDao = db.favoriteDao(),
                    json = json,
                    dispatchers = dispatchers
                )

                val restaurants = (1..10).map { idx ->
                    RestaurantEntity(
                        id = "rest_${idx.toString().padStart(2, '0')}",
                        name = "Kitchen $idx",
                        cuisinesCsv = if (idx % 2 == 0) "Pizza, Italian" else "North Indian",
                        rating = 3.8 + (idx * 0.1),
                        ratingCount = 100 + idx,
                        deliveryTimeMin = 20 + idx,
                        deliveryFee = 25.0,
                        minOrderAmount = 99.0,
                        offerText = null,
                        isPureVeg = idx % 2 == 0,
                        isOpen = true,
                        isPopular = idx <= 4,
                        isRecommended = true,
                        imageKey = "indian",
                        address = "Road $idx",
                        distanceKm = 1.0 + idx * 0.2,
                        latitude = 23.0,
                        longitude = 72.5,
                        lastUpdatedMs = 1L
                    )
                }
                db.catalogDao().upsertRestaurants(restaurants)

                val menuItems = (1..12).map { idx ->
                    FoodItemEntity(
                        id = "food_${idx.toString().padStart(2, '0')}",
                        restaurantId = "rest_01",
                        name = if (idx % 2 == 0) "Veg Pizza $idx" else "Spicy Tikka $idx",
                        description = "Freshly made dish $idx",
                        categoryName = if (idx <= 6) "Mains" else "Starters",
                        price = 150.0 + idx * 10,
                        originalPrice = null,
                        isVeg = idx % 2 == 0,
                        isAvailable = true,
                        isRecommended = false,
                        isBestseller = false,
                        rating = 4.4,
                        imageKey = "pizza",
                        addonsJson = "[]",
                        spiceLevel = "MEDIUM"
                    )
                }
                db.catalogDao().upsertFoodItems(menuItems)

                // Restaurant pages: 10 total with pageSize 4 -> pages of 4, 4, 2
                val rPage0 = (dataSource.loadRestaurantsPage(
                    userId = "u1",
                    request = PageRequest(pageIndex = 0, pageSize = 4),
                    filter = RestaurantPageFilter(sortBy = RestaurantSortOption.RATING)
                ) as AppResult.Success).data
                val rPage1 = (dataSource.loadRestaurantsPage(
                    userId = "u1",
                    request = PageRequest(pageIndex = 1, pageSize = 4),
                    filter = RestaurantPageFilter(sortBy = RestaurantSortOption.RATING)
                ) as AppResult.Success).data
                val rPage2 = (dataSource.loadRestaurantsPage(
                    userId = "u1",
                    request = PageRequest(pageIndex = 2, pageSize = 4),
                    filter = RestaurantPageFilter(sortBy = RestaurantSortOption.RATING)
                ) as AppResult.Success).data

                assertEquals(4, rPage0.items.size)
                assertTrue(rPage0.hasNextPage)
                assertEquals(4, rPage1.items.size)
                assertTrue(rPage1.hasNextPage)
                assertEquals(2, rPage2.items.size)
                assertFalse(rPage2.hasNextPage)

                val allRestaurantIds = (rPage0.items + rPage1.items + rPage2.items).map { it.id }
                assertEquals(10, allRestaurantIds.distinct().size)
                assertEquals("rest_10", allRestaurantIds.first()) // highest rating first

                // Filtered pure veg restaurants -> 5 total
                val vegPage = (dataSource.loadRestaurantsPage(
                    userId = "u1",
                    request = PageRequest(pageIndex = 0, pageSize = 6),
                    filter = RestaurantPageFilter(pureVegOnly = true)
                ) as AppResult.Success).data
                assertEquals(5, vegPage.items.size)
                assertFalse(vegPage.hasNextPage)
                assertTrue(vegPage.items.all { it.isPureVeg })

                // Menu pagination with search & veg filter -> 6 veg pizzas, pageSize 4 -> 4 + 2
                val mPage0 = (dataSource.loadMenuPage(
                    restaurantId = "rest_01",
                    request = PageRequest(pageIndex = 0, pageSize = 4),
                    filter = MenuPageFilter(vegOnly = true, searchQuery = "Pizza")
                ) as AppResult.Success).data
                val mPage1 = (dataSource.loadMenuPage(
                    restaurantId = "rest_01",
                    request = PageRequest(pageIndex = 1, pageSize = 4),
                    filter = MenuPageFilter(vegOnly = true, searchQuery = "Pizza")
                ) as AppResult.Success).data
                assertEquals(4, mPage0.items.size)
                assertTrue(mPage0.hasNextPage)
                assertEquals(2, mPage1.items.size)
                assertFalse(mPage1.hasNextPage)
                assertEquals(6, (mPage0.items + mPage1.items).map { it.id }.distinct().size)
            } finally {
                db.close()
            }
        }

    @Test
    fun `CartViewModel updates cooking notes draft synchronously, debounces Room writes, and flushes before checkout`() =
        runTest(testDispatcher) {
            val cartRepo = mockk<CartRepository>(relaxed = true)
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)
            val item = TestFixtures.cartItem(itemId = "food_note_1", unitPrice = 250.0, quantity = 1)

            every { cartRepo.observeCartItems() } returns flowOf(listOf(item))
            every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
            every { restaurantRepo.observeCoupons() } returns flowOf(emptyList())
            every { restaurantRepo.observeMenu(any()) } returns flowOf(emptyList())
            coEvery { cartRepo.syncPendingOperations() } returns AppResult.Success(emptyList())
            coEvery { cartRepo.validateCartWithServer() } returns AppResult.Success(emptyList())

            val viewModel = CartViewModel(
                observeCartItems = ObserveCartItemsUseCase(cartRepo),
                observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
                observeCoupons = ObserveCouponsUseCase(restaurantRepo),
                observeMenu = ObserveMenuUseCase(restaurantRepo),
                addToCart = AddToCartUseCase(cartRepo),
                updateCartQuantity = UpdateCartQuantityUseCase(cartRepo),
                removeCartItem = RemoveCartItemUseCase(cartRepo),
                updateCookingInstructions = UpdateCookingInstructionsUseCase(cartRepo),
                clearCart = ClearCartUseCase(cartRepo),
                syncPendingCartOperations = SyncPendingCartOperationsUseCase(cartRepo),
                validateCart = ValidateCartUseCase(cartRepo),
                getCouponByCode = GetCouponByCodeUseCase(restaurantRepo),
                calculateCartTotals = CalculateCartTotalsUseCase(),
                validateCoupon = ValidateCouponUseCase(),
                connectivityObserver = connectivity
            )
            advanceUntilIdle()

            // Rapid typing updates state synchronously without firing premature DB writes
            viewModel.onIntent(CartIntent.UpdateInstructions("food_note_1", "No "))
            viewModel.onIntent(CartIntent.UpdateInstructions("food_note_1", "No onions"))
            viewModel.onIntent(CartIntent.UpdateInstructions("food_note_1", "No onions, extra spicy"))
            assertEquals("No onions, extra spicy", viewModel.state.value.cookingInstructionsFor(item))
            coVerify(exactly = 0) { cartRepo.updateCookingInstructions(any(), any()) }

            // Advancing past 350ms debounce persists exactly once with the latest text
            advanceTimeBy(400L)
            advanceUntilIdle()
            coVerify(exactly = 1) {
                cartRepo.updateCookingInstructions("food_note_1", "No onions, extra spicy")
            }

            // Typing right before tapping ProceedToCheckout flushes immediately without waiting for debounce
            viewModel.onIntent(CartIntent.UpdateInstructions("food_note_1", "Extra crispy please"))
            viewModel.onIntent(CartIntent.ProceedToCheckout)
            advanceUntilIdle()
            coVerify(exactly = 1) {
                cartRepo.updateCookingInstructions("food_note_1", "Extra crispy please")
            }
        }

    @Test
    fun `VoiceAssistantBottomSheet anchors welcome heading and 4 suggestion chips above input bar and hides them once chat starts`() {
        var uiState by mutableStateOf(
            VoiceAssistantUiState(
                isSheetOpen = true,
                sheetDetent = VoiceSheetDetent.HALF_EXPANDED,
                phase = VoiceAssistantPhase.IDLE,
                messages = emptyList()
            )
        )
        var tappedPromptId: String? = null

        composeRule.setContent {
            FoodFlowTheme {
                VoiceAssistantBottomSheet(
                    uiState = uiState,
                    onIntent = { intent ->
                        if (intent is VoiceAssistantIntent.SelectQuickPrompt) {
                            tappedPromptId = intent.prompt.id
                        }
                    }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_welcome_container").assertIsDisplayed()
        composeRule.onNodeWithText("How can I help you order?").assertIsDisplayed()
        composeRule.onNodeWithText("Find restaurants near me").assertIsDisplayed()
        composeRule.onNodeWithText("Show popular pizzas").assertIsDisplayed()
        composeRule.onNodeWithText("Find food under ₹200").assertIsDisplayed()
        composeRule.onNodeWithText("Show vegetarian meals").assertIsDisplayed()

        // Welcome heading & chips sit directly above the input bar (bottom-anchored inside viewport)
        val headingBounds = composeRule.onNodeWithTag("voice_welcome_heading").getUnclippedBoundsInRoot()
        val chipsBounds = composeRule.onNodeWithTag("voice_quick_prompts_row").getUnclippedBoundsInRoot()
        val inputBounds = composeRule.onNodeWithTag("voice_text_input").getUnclippedBoundsInRoot()
        assertTrue(headingBounds.bottom.value <= chipsBounds.top.value + 4f)
        assertTrue(chipsBounds.bottom.value <= inputBounds.top.value + 4f)

        // Clicking a suggestion chip emits SelectQuickPrompt
        composeRule.onNodeWithTag("voice_quick_prompt_popular_pizzas").performClick()
        assertEquals("popular_pizzas", tappedPromptId)

        // Once conversation starts, welcome state disappears smoothly and never reappears
        uiState = uiState.copy(
            messages = listOf(
                VoiceConversationMessage(
                    id = "m1",
                    turnId = 1L,
                    role = VoiceMessageRole.USER,
                    text = "Show popular pizzas",
                    timestampMs = 1_000L
                )
            )
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_welcome_container").assertDoesNotExist()
        composeRule.onNodeWithText("How can I help you order?").assertDoesNotExist()
        composeRule.onNodeWithTag("voice_conversation_list").assertIsDisplayed()
    }
}
