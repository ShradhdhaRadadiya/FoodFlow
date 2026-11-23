package com.foodflow.voice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.foodflow.core.common.AppResult
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.model.VoiceStructuredResponse
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.RestaurantRepository
import com.foodflow.domain.repository.VoiceAssistantController
import com.foodflow.domain.repository.VoiceAssistantRepository
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.voice.ConnectVoiceAssistantUseCase
import com.foodflow.domain.usecase.voice.DisconnectVoiceAssistantUseCase
import com.foodflow.domain.usecase.voice.ExecuteAssistantActionUseCase
import com.foodflow.domain.usecase.voice.ObserveConversationUseCase
import com.foodflow.domain.usecase.voice.ObserveVoiceAssistantStateUseCase
import com.foodflow.domain.usecase.voice.RestoreConversationUseCase
import com.foodflow.domain.usecase.voice.SendTextMessageUseCase
import com.foodflow.domain.usecase.voice.StartVoiceRecordingUseCase
import com.foodflow.domain.usecase.voice.StopAssistantUseCase
import com.foodflow.feature.voice.AssistantControls
import com.foodflow.feature.voice.AssistantResponseRenderer
import com.foodflow.feature.voice.VoiceAssistantBottomSheet
import com.foodflow.feature.voice.VoiceAssistantIntent
import com.foodflow.feature.voice.VoiceAssistantScreen
import com.foodflow.feature.voice.VoiceAssistantUiState
import com.foodflow.feature.voice.VoiceAssistantViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class VoiceAssistantUiRefactorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `AssistantControls renders ONLY Speak and Stop with zero position or size shift across states`() {
        var isListening by mutableStateOf(false)
        var canStop by mutableStateOf(false)
        var speakClicks = 0
        var stopClicks = 0

        composeRule.setContent {
            FoodFlowTheme {
                AssistantControls(
                    isListening = isListening,
                    canStop = canStop,
                    onSpeakClick = { speakClicks++ },
                    onStopClick = { stopClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        // Verify removed controls DO NOT exist anywhere
        composeRule.onNodeWithTag("voice_pause_resume_button").assertDoesNotExist()
        composeRule.onNodeWithTag("voice_mute_button").assertDoesNotExist()
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
        composeRule.onNodeWithText("Mute").assertDoesNotExist()

        // Capture bounds in IDLE state
        composeRule.onNodeWithTag("voice_speak_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("voice_stop_button").assertIsDisplayed().assertIsNotEnabled()
        val idleSpeakBounds = composeRule.onNodeWithTag("voice_speak_button").getUnclippedBoundsInRoot()
        val idleStopBounds = composeRule.onNodeWithTag("voice_stop_button").getUnclippedBoundsInRoot()

        // Transition to LISTENING state
        isListening = true
        canStop = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_stop_button").assertIsEnabled()
        val listeningSpeakBounds = composeRule.onNodeWithTag("voice_speak_button").getUnclippedBoundsInRoot()
        val listeningStopBounds = composeRule.onNodeWithTag("voice_stop_button").getUnclippedBoundsInRoot()

        // Zero horizontal or vertical shift between IDLE and LISTENING
        assertTrue(abs(idleSpeakBounds.left.value - listeningSpeakBounds.left.value) < 0.5f)
        assertTrue(abs(idleSpeakBounds.right.value - listeningSpeakBounds.right.value) < 0.5f)
        assertTrue(abs(idleStopBounds.left.value - listeningStopBounds.left.value) < 0.5f)
        assertTrue(abs(idleStopBounds.right.value - listeningStopBounds.right.value) < 0.5f)

        // Transition to SPEAKING state
        isListening = false
        canStop = true
        composeRule.waitForIdle()

        val speakingSpeakBounds = composeRule.onNodeWithTag("voice_speak_button").getUnclippedBoundsInRoot()
        val speakingStopBounds = composeRule.onNodeWithTag("voice_stop_button").getUnclippedBoundsInRoot()
        assertTrue(abs(idleSpeakBounds.left.value - speakingSpeakBounds.left.value) < 0.5f)
        assertTrue(abs(idleStopBounds.left.value - speakingStopBounds.left.value) < 0.5f)

        // Verify click callbacks
        composeRule.onNodeWithTag("voice_speak_button").performClick()
        composeRule.onNodeWithTag("voice_stop_button").performClick()
        assertEquals(1, speakClicks)
        assertEquals(1, stopClicks)
    }

    @Test
    fun `AssistantResponseRenderer renders FoodFlow RestaurantCard without duplicate text bubble`() {
        var openedRestaurantId: String? = null
        val restaurant = sampleRestaurant()
        val msg = VoiceConversationMessage(
            id = "turn_rest_1",
            turnId = 1L,
            role = VoiceMessageRole.TOOL_ACTION,
            text = "Top restaurants for you",
            timestampMs = 1_000L,
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                headline = "Top restaurants for you",
                restaurants = listOf(restaurant)
            )
        )

        composeRule.setContent {
            FoodFlowTheme {
                AssistantResponseRenderer(
                    message = msg,
                    cartQuantitiesByFoodId = emptyMap(),
                    onOpenRestaurant = { openedRestaurantId = it },
                    onToggleFavoriteRestaurant = {},
                    onAddFoodToCart = {},
                    onIncrementFoodInCart = {},
                    onDecrementFoodInCart = {},
                    onOpenCart = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("assistant_response_headline").assertIsDisplayed()
        composeRule.onNodeWithTag("restaurant_card_rest_spice").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant_text_response").assertDoesNotExist()

        composeRule.onNodeWithTag("restaurant_card_rest_spice").performClick()
        assertEquals("rest_spice", openedRestaurantId)
    }

    @Test
    fun `AssistantResponseRenderer renders FoodFlow FoodItemCard with Add action and no duplicate text bubble`() {
        var addedFood: FoodItem? = null
        val food = sampleFoodItem()
        val msg = VoiceConversationMessage(
            id = "turn_food_1",
            turnId = 2L,
            role = VoiceMessageRole.TOOL_ACTION,
            text = "Veg dishes under ₹200",
            timestampMs = 2_000L,
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.FOOD_RECOMMENDATIONS,
                headline = "Veg dishes under ₹200",
                foodItems = listOf(food),
                foodRestaurantNames = mapOf("rest_spice" to "Spice Route Kitchen")
            )
        )

        composeRule.setContent {
            FoodFlowTheme {
                AssistantResponseRenderer(
                    message = msg,
                    cartQuantitiesByFoodId = emptyMap(),
                    onOpenRestaurant = {},
                    onToggleFavoriteRestaurant = {},
                    onAddFoodToCart = { addedFood = it },
                    onIncrementFoodInCart = {},
                    onDecrementFoodInCart = {},
                    onOpenCart = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("food_item_food_paneer_wrap").assertIsDisplayed()
        composeRule.onNodeWithTag("assistant_text_response").assertDoesNotExist()
        composeRule.onNodeWithText("ADD").performClick()
        assertEquals("food_paneer_wrap", addedFood?.id)
    }

    @Test
    fun `VoiceAssistantBottomSheet renders restaurant cards inside single unified sheet with ZERO external floating tray`() {
        val restaurant = sampleRestaurant()
        val userMsg = VoiceConversationMessage(
            id = "turn_user_3",
            turnId = 3L,
            role = VoiceMessageRole.USER,
            text = "Show top restaurants",
            timestampMs = 2_900L
        )
        val responseMsg = VoiceConversationMessage(
            id = "turn_tray_1",
            turnId = 3L,
            role = VoiceMessageRole.TOOL_ACTION,
            text = "Top restaurants for you",
            timestampMs = 3_000L,
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                headline = "Top restaurants for you",
                restaurants = listOf(restaurant)
            )
        )

        val state = VoiceAssistantUiState(
            isSheetOpen = true,
            sheetDetent = VoiceSheetDetent.HALF_EXPANDED,
            phase = VoiceAssistantPhase.IDLE,
            statusText = "Ready when you are",
            messages = listOf(userMsg, responseMsg)
        )

        composeRule.setContent {
            FoodFlowTheme {
                VoiceAssistantBottomSheet(
                    uiState = state,
                    onIntent = {}
                )
            }
        }
        composeRule.waitForIdle()

        // External floating tray must NOT exist; everything renders inside the unified sheet
        composeRule.onNodeWithTag("voice_foreground_result_tray").assertDoesNotExist()
        composeRule.onNodeWithTag("voice_assistant_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_user_query_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("restaurant_card_rest_spice").assertIsDisplayed()

        // Chronological order: User query sits strictly ABOVE the restaurant card response
        val userBounds = composeRule.onNodeWithTag("voice_user_query_chip").getUnclippedBoundsInRoot()
        val cardBounds = composeRule.onNodeWithTag("restaurant_card_rest_spice").getUnclippedBoundsInRoot()
        assertTrue(
            "User message (${userBounds.bottom}) must render strictly above assistant restaurant card (${cardBounds.top})",
            userBounds.bottom <= cardBounds.top
        )
    }

    @Test
    fun `clicking close button on VoiceAssistantBottomSheet dispatches CloseAssistant intent`() {
        val intents = mutableListOf<VoiceAssistantIntent>()
        val state = VoiceAssistantUiState(
            isSheetOpen = true,
            sheetDetent = VoiceSheetDetent.HALF_EXPANDED,
            phase = VoiceAssistantPhase.IDLE,
            statusText = "Ready when you are"
        )

        composeRule.setContent {
            FoodFlowTheme {
                VoiceAssistantBottomSheet(
                    uiState = state,
                    onIntent = { intents += it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_close_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(
            "Close button MUST dispatch VoiceAssistantIntent.CloseAssistant",
            intents.contains(VoiceAssistantIntent.CloseAssistant)
        )
        assertFalse(
            "Close button must NOT dispatch HideAssistant without closing session",
            intents.contains(VoiceAssistantIntent.HideAssistant)
        )
    }

    @Test
    fun `removing VoiceAssistantScreen from composition dispatches CloseAssistant intent`() {
        val intents = mutableListOf<VoiceAssistantIntent>()
        var showScreen by mutableStateOf(true)
        val state = VoiceAssistantUiState(
            isSheetOpen = true,
            sheetDetent = VoiceSheetDetent.HALF_EXPANDED,
            phase = VoiceAssistantPhase.IDLE,
            statusText = "Ready when you are"
        )

        composeRule.setContent {
            FoodFlowTheme {
                if (showScreen) {
                    VoiceAssistantScreen(
                        uiState = state,
                        showFloatingTrigger = true,
                        floatingBottomOffset = 20.dp,
                        requestPermissionSignal = 0,
                        onIntent = { intents += it }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertFalse(intents.contains(VoiceAssistantIntent.CloseAssistant))

        // Clear / remove VoiceAssistantScreen from composition
        showScreen = false
        composeRule.waitForIdle()

        assertTrue(
            "Disposing VoiceAssistantScreen MUST dispatch CloseAssistant",
            intents.contains(VoiceAssistantIntent.CloseAssistant)
        )
    }

    @Test
    fun `VoiceAssistantViewModel onCleared releases voice controller resources`() {
        val fakeController = RecordingReleaseController()
        val fakeVoiceRepo = NoOpVoiceAssistantRepository(fakeController)
        val fakeCartRepo = NoOpCartRepository()
        val fakeRestaurantRepo = NoOpRestaurantRepository()

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VoiceAssistantViewModel(
                    controller = fakeController,
                    connectVoiceAssistant = ConnectVoiceAssistantUseCase(fakeVoiceRepo),
                    disconnectVoiceAssistant = DisconnectVoiceAssistantUseCase(fakeVoiceRepo),
                    startVoiceRecording = StartVoiceRecordingUseCase(fakeVoiceRepo),
                    stopAssistant = StopAssistantUseCase(fakeVoiceRepo),
                    sendTextMessage = SendTextMessageUseCase(fakeVoiceRepo),
                    executeAssistantAction = ExecuteAssistantActionUseCase(fakeVoiceRepo),
                    restoreConversation = RestoreConversationUseCase(fakeVoiceRepo),
                    updateCartQuantity = UpdateCartQuantityUseCase(fakeCartRepo),
                    removeCartItem = RemoveCartItemUseCase(fakeCartRepo),
                    toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(fakeRestaurantRepo),
                    observeAssistantState = ObserveVoiceAssistantStateUseCase(fakeVoiceRepo),
                    observeConversation = ObserveConversationUseCase(fakeVoiceRepo),
                    observeCartItems = ObserveCartItemsUseCase(fakeCartRepo)
                ) as T
            }
        }

        ViewModelProvider(store, factory)[VoiceAssistantViewModel::class.java]
        assertEquals(0, fakeController.releaseResourcesCount)

        // Clearing the ViewModelStore triggers ViewModel.onCleared()
        store.clear()
        assertEquals(1, fakeController.releaseResourcesCount)
    }

    private class RecordingReleaseController : VoiceAssistantController {
        var releaseResourcesCount = 0
        private val state = MutableStateFlow(VoiceAssistantSnapshot())
        private val conversationFlow = MutableStateFlow(
            VoiceConversationContext(conversationId = "conv_test", sessionId = "sess_test")
        )
        private val nav = MutableSharedFlow<VoiceNavigationTarget>()

        override fun observeAssistantState(): StateFlow<VoiceAssistantSnapshot> = state
        override fun observeConversation(): StateFlow<VoiceConversationContext> = conversationFlow
        override fun observeNavigationRequests(): SharedFlow<VoiceNavigationTarget> = nav
        override suspend fun openAssistant() = Unit
        override suspend fun hideAssistant() = Unit
        override suspend fun setSheetDetent(detent: VoiceSheetDetent) = Unit
        override suspend fun closeAssistant() = Unit
        override suspend fun connectSession(isManualRetry: Boolean) = Unit
        override suspend fun disconnectSession(isExplicitClose: Boolean) = Unit
        override suspend fun startListening() = Unit
        override suspend fun stopListening() = Unit
        override suspend fun stopCurrentInteraction() = Unit
        override suspend fun pauseAssistant() = Unit
        override suspend fun resumeAssistant() = Unit
        override suspend fun interruptAssistant(reason: String) = Unit
        override suspend fun toggleMute() = Unit
        override suspend fun sendTextMessage(text: String) = Unit
        override suspend fun sendManualVoiceInput(pcmBytes: ByteArray, sampleRateHz: Int) = Unit
        override suspend fun executeAction(action: VoiceActionRequest) = VoiceActionResult(true, "")
        override suspend fun confirmPendingAction() = Unit
        override suspend fun dismissPendingAction() = Unit
        override suspend fun restoreConversation(): VoiceConversationContext = conversationFlow.value
        override fun updateScreenContext(screenName: String, restaurantId: String?, restaurantName: String?) = Unit
        override fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) = Unit
        override fun onAppBackgrounded() = Unit
        override fun onAppForegrounded() = Unit
        override fun releaseResources() {
            releaseResourcesCount++
        }
    }

    private class NoOpVoiceAssistantRepository(
        private val controller: VoiceAssistantController
    ) : VoiceAssistantRepository {
        override val assistantState: StateFlow<VoiceAssistantSnapshot> = controller.observeAssistantState()
        override val conversation: StateFlow<VoiceConversationContext> = controller.observeConversation()
        override val navigationRequests: SharedFlow<VoiceNavigationTarget> = controller.observeNavigationRequests()

        override suspend fun openAssistant() = controller.openAssistant()
        override suspend fun hideAssistant() = controller.hideAssistant()
        override suspend fun closeAssistant() = controller.closeAssistant()
        override suspend fun setSheetDetent(detent: VoiceSheetDetent) = controller.setSheetDetent(detent)
        override suspend fun connect(isManualRetry: Boolean) = controller.connectSession(isManualRetry)
        override suspend fun disconnect(isExplicitClose: Boolean) = controller.disconnectSession(isExplicitClose)
        override suspend fun startRecording() = controller.startListening()
        override suspend fun stopRecording() = controller.stopListening()
        override suspend fun stopAssistant() = controller.stopCurrentInteraction()
        override suspend fun sendVoiceChunk(pcmBytes: ByteArray, sampleRateHz: Int) = Unit
        override suspend fun sendTextMessage(text: String) = controller.sendTextMessage(text)
        override suspend fun interruptResponse(reason: String) = controller.interruptAssistant(reason)
        override suspend fun pause() = controller.pauseAssistant()
        override suspend fun resume() = controller.resumeAssistant()
        override suspend fun toggleMute() = controller.toggleMute()
        override suspend fun executeAction(action: VoiceActionRequest) = controller.executeAction(action)
        override suspend fun confirmPendingAction() = controller.confirmPendingAction()
        override suspend fun dismissPendingAction() = controller.dismissPendingAction()
        override suspend fun restoreConversation() = controller.restoreConversation()
        override fun updateScreenContext(screenName: String, restaurantId: String?, restaurantName: String?) = Unit
        override fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) = Unit
        override fun onAppBackgrounded() = controller.onAppBackgrounded()
        override fun onAppForegrounded() = controller.onAppForegrounded()
        override fun releaseResources() = controller.releaseResources()
    }

    private class NoOpCartRepository : CartRepository {
        override fun observeCartItems(): Flow<List<CartItem>> = flowOf(emptyList())
        override fun observePendingOperationCount(): Flow<Int> = flowOf(0)
        override fun observeLastSyncTimestamp(): Flow<Long> = flowOf(0L)
        override suspend fun addItem(
            restaurant: Restaurant,
            foodItem: FoodItem,
            addons: List<FoodAddon>,
            cookingInstructions: String,
            clearExistingDifferentRestaurant: Boolean
        ): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun updateQuantity(itemId: String, quantity: Int): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun removeItem(itemId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun updateCookingInstructions(itemId: String, instructions: String): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun clearCart(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun syncPendingOperations(): AppResult<List<CartReconciliationNotice>> =
            AppResult.Success(emptyList())
        override suspend fun validateCartWithServer(): AppResult<List<CartReconciliationNotice>> =
            AppResult.Success(emptyList())
        override fun scheduleDeferredSync() = Unit
    }

    private class NoOpRestaurantRepository : RestaurantRepository {
        override fun observeCategories(): Flow<List<FoodCategory>> = flowOf(emptyList())
        override fun observeRestaurants(): Flow<List<Restaurant>> = flowOf(emptyList())
        override fun observeRestaurant(restaurantId: String): Flow<Restaurant?> = flowOf(null)
        override fun observeMenu(restaurantId: String): Flow<List<FoodItem>> = flowOf(emptyList())
        override fun observeCoupons(): Flow<List<Coupon>> = flowOf(emptyList())
        override fun observeFavoriteRestaurants(): Flow<List<Restaurant>> = flowOf(emptyList())
        override fun observeRecentSearches(): Flow<List<String>> = flowOf(emptyList())
        override fun searchRestaurantsLocal(query: String): Flow<List<Restaurant>> = flowOf(emptyList())
        override fun searchFoodItemsLocal(query: String): Flow<List<FoodItem>> = flowOf(emptyList())
        override suspend fun getRestaurantsPage(
            request: PageRequest,
            filter: RestaurantPageFilter
        ): AppResult<PagedResult<Restaurant>> = AppResult.Success(
            PagedResult(
                items = emptyList(),
                pageIndex = request.pageIndex,
                pageSize = request.pageSize,
                totalCount = 0,
                hasNextPage = false
            )
        )
        override suspend fun getRestaurantMenuPage(
            restaurantId: String,
            request: PageRequest,
            filter: MenuPageFilter
        ): AppResult<PagedResult<FoodItem>> = AppResult.Success(
            PagedResult(
                items = emptyList(),
                pageIndex = request.pageIndex,
                pageSize = request.pageSize,
                totalCount = 0,
                hasNextPage = false
            )
        )
        override suspend fun saveSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
        override suspend fun refreshHomeCatalog(): AppResult<Long> = AppResult.Success(0L)
        override suspend fun refreshRestaurantMenu(restaurantId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun toggleFavorite(restaurantId: String): AppResult<Boolean> = AppResult.Success(true)
        override suspend fun getCouponByCode(code: String): Coupon? = null
    }

    private fun sampleRestaurant() = Restaurant(
        id = "rest_spice",
        name = "Spice Route Kitchen",
        cuisines = listOf("North Indian", "Biryani"),
        rating = 4.6,
        ratingCount = 420,
        deliveryTimeMin = 25,
        deliveryFee = 30.0,
        minOrderAmount = 99.0,
        offerText = "20% OFF",
        isPureVeg = false,
        isOpen = true,
        isPopular = true,
        isRecommended = true,
        isFavorite = false,
        imageKey = "indian",
        address = "SG Highway",
        distanceKm = 2.1,
        latitude = 23.0225,
        longitude = 72.5714
    )

    private fun sampleFoodItem() = FoodItem(
        id = "food_paneer_wrap",
        restaurantId = "rest_spice",
        name = "Paneer Tikka Wrap",
        description = "Smoky cottage cheese wrapped in whole wheat paratha",
        categoryName = "Wraps",
        price = 165.0,
        originalPrice = 190.0,
        isVeg = true,
        isAvailable = true,
        isRecommended = true,
        isBestseller = true,
        rating = 4.6,
        imageKey = "indian",
        addons = emptyList(),
        spiceLevel = "Medium"
    )
}
