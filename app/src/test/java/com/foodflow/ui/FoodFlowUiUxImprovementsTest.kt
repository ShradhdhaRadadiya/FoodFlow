package com.foodflow.ui

import android.app.Application
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.foodflow.app.navigation.BottomNavScrollState
import com.foodflow.app.navigation.FloatingCapsuleBottomNavBar
import com.foodflow.app.navigation.FoodFlowTopLevelDestinations
import com.foodflow.app.navigation.formatBadgeCount
import com.foodflow.core.designsystem.components.FloatingCartPreviewBar
import com.foodflow.core.designsystem.components.OrderDeliveryJourneyCard
import com.foodflow.core.designsystem.components.OrderJourneyStage
import com.foodflow.core.designsystem.components.resolveOrderJourneyStage
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.data.voice.VoiceConversationStoreImpl
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceRecordingState
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.feature.home.HomeScreenContent
import com.foodflow.feature.home.HomeUiState
import com.foodflow.feature.tracking.PhoneDialerHelper
import com.foodflow.feature.voice.AssistantInputBar
import com.foodflow.feature.voice.FloatingAssistantPositionMath
import com.foodflow.feature.voice.FloatingButtonBounds
import com.foodflow.feature.voice.VoiceAssistantBottomSheet
import com.foodflow.feature.voice.VoiceAssistantUiState
import com.foodflow.feature.voice.VoiceAssistantWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FoodFlowUiUxImprovementsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `HomeScreen shows outlined Favorite icon without badge when empty and filled heart with badge when populated`() {
        var favoriteCount by mutableIntStateOf(0)
        var openedFavorites = false

        composeRule.setContent {
            FoodFlowTheme {
                HomeScreenContent(
                    state = HomeUiState(isLoading = false, favoriteCount = favoriteCount),
                    snackbarHostState = SnackbarHostState(),
                    onIntent = {},
                    onOpenLocation = {},
                    onOpenSearch = {},
                    onOpenRestaurantList = {},
                    onOpenRestaurantDetail = {},
                    onOpenTracking = {},
                    onOpenFavorites = { openedFavorites = true }
                )
            }
        }
        composeRule.waitForIdle()

        // Developer wrench button is removed
        composeRule.onNodeWithTag("home_debug_lab_button").assertDoesNotExist()
        // Empty favorites -> icon shown, badge NOT shown
        composeRule.onNodeWithTag("home_favorite_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_favorite_badge", useUnmergedTree = true).assertDoesNotExist()

        // Add favorites -> badge appears immediately with synchronized count
        favoriteCount = 3
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_favorite_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()

        // Tap opens Favorites screen
        composeRule.onNodeWithTag("home_favorite_button").performClick()
        assertTrue(openedFavorites)
    }

    @Test
    fun `FloatingCapsuleBottomNavBar toggles labels cleanly and keeps Cart badge visible in both modes`() {
        var activeTab by mutableIntStateOf(0)
        var showLabels by mutableStateOf(false)
        var cartCount by mutableIntStateOf(4)

        composeRule.setContent {
            FoodFlowTheme {
                FloatingCapsuleBottomNavBar(
                    destinations = FoodFlowTopLevelDestinations,
                    activeTabIndex = activeTab,
                    cartCount = cartCount,
                    showLabels = showLabels,
                    onSelectTab = { activeTab = it }
                )
            }
        }
        composeRule.waitForIdle()

        // Compact icon-only mode at top of screen: 5 icons visible, labels hidden, Cart badge visible
        composeRule.onNodeWithTag("floating_capsule_bottom_nav").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_cart").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_home_label", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("cart_badge_count", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("4", useUnmergedTree = true).assertIsDisplayed()

        // Expanded mode on scroll reveal: labels appear AND Cart badge stays visible
        showLabels = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_home_label", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("cart_badge_count", useUnmergedTree = true).assertIsDisplayed()

        // Select Cart tab & exceed 99 items -> badge stays visible formatted as 99+
        activeTab = 2
        cartCount = 120
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cart_badge_count", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("99+", formatBadgeCount(120))
    }

    @Test
    fun `BottomNavScrollState is expanded at top, collapses on downward scroll, expands on upward scroll, and stays independent per tab`() {
        val scrollState = BottomNavScrollState(scrollHysteresisPx = 20f)
        scrollState.setActiveTab(0)

        // Default at top -> expanded with icons + labels
        assertTrue(scrollState.shouldShowLabels(0))
        assertFalse(scrollState.isCollapsed(0))

        // Scroll downward into content past hysteresis -> collapses to compact icon-only capsule
        scrollState.onTabAtTopChanged(0, false)
        scrollState.onVerticalScrollDelta(0, -40f)
        assertTrue(scrollState.isCollapsed(0))
        assertFalse(scrollState.shouldShowLabels(0))

        // Independent per-tab state: Tab 1 is still at top -> expanded
        assertTrue(scrollState.shouldShowLabels(1))
        assertFalse(scrollState.isCollapsed(1))

        // Scroll upward toward top past hysteresis -> expands with labels again
        scrollState.onVerticalScrollDelta(0, 30f)
        assertFalse(scrollState.isCollapsed(0))
        assertTrue(scrollState.shouldShowLabels(0))

        // Collapse again and return to top -> forces expanded state
        scrollState.onVerticalScrollDelta(0, -35f)
        assertTrue(scrollState.isCollapsed(0))
        scrollState.onTabAtTopChanged(0, true)
        assertFalse(scrollState.isCollapsed(0))
        assertTrue(scrollState.shouldShowLabels(0))
    }

    @Test
    fun `FloatingCartPreviewBar enters on first item, updates in place, triggers View Cart, and exits when empty`() {
        var count by mutableIntStateOf(0)
        var total by mutableStateOf(0.0)
        var viewCartClicks = 0

        composeRule.setContent {
            FoodFlowTheme {
                FloatingCartPreviewBar(
                    itemCount = count,
                    totalAmount = total,
                    restaurantName = "Spice Route Kitchen",
                    onViewCart = { viewCartClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("floating_cart_preview_bar").assertDoesNotExist()

        count = 1
        total = 240.0
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("floating_cart_preview_bar").assertIsDisplayed()
        composeRule.onNodeWithText("1 item").assertIsDisplayed()
        composeRule.onNodeWithText("View Cart").assertIsDisplayed()

        count = 3
        total = 690.0
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 items").assertIsDisplayed()
        composeRule.onNodeWithTag("floating_cart_preview_bar").performClick()
        assertEquals(1, viewCartClicks)

        count = 0
        total = 0.0
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("floating_cart_preview_bar").assertDoesNotExist()
    }

    @Test
    fun `OrderDeliveryJourneyCard maps real OrderStatus accurately without premature delivery visuals`() {
        assertEquals(OrderJourneyStage.CONFIRMED, resolveOrderJourneyStage(OrderStatus.PLACED, 0f))
        assertEquals(OrderJourneyStage.CONFIRMED, resolveOrderJourneyStage(OrderStatus.ACCEPTED, 0f))
        assertEquals(OrderJourneyStage.PREPARING, resolveOrderJourneyStage(OrderStatus.PREPARING, 0f))
        assertEquals(OrderJourneyStage.READY_FOR_PICKUP, resolveOrderJourneyStage(OrderStatus.READY_FOR_PICKUP, 0f))
        assertEquals(OrderJourneyStage.PARTNER_ASSIGNED, resolveOrderJourneyStage(OrderStatus.PARTNER_ASSIGNED, 0f))
        assertEquals(OrderJourneyStage.PICKED_UP, resolveOrderJourneyStage(OrderStatus.PICKED_UP, 0.1f))
        assertEquals(OrderJourneyStage.ON_THE_WAY, resolveOrderJourneyStage(OrderStatus.PICKED_UP, 0.55f))
        assertEquals(OrderJourneyStage.DELIVERED, resolveOrderJourneyStage(OrderStatus.DELIVERED, 1f))
        assertEquals(OrderJourneyStage.CANCELLED, resolveOrderJourneyStage(OrderStatus.CANCELLED, 0f))

        val order = sampleOrder(OrderStatus.PREPARING)
        composeRule.setContent {
            FoodFlowTheme {
                OrderDeliveryJourneyCard(order = order)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("order_delivery_journey_card").assertIsDisplayed()
        composeRule.onNodeWithTag("order_journey_stage_preparing").assertIsDisplayed()
        composeRule.onNodeWithTag("order_journey_stage_on_the_way").assertDoesNotExist()
    }

    @Test
    fun `PhoneDialerHelper sanitizes valid phone numbers into ACTION_DIAL intents and rejects invalid values`() {
        assertEquals("+919876543210", PhoneDialerHelper.sanitizeDialablePhone("+91 98765-43210"))
        assertNull(PhoneDialerHelper.sanitizeDialablePhone(""))
        assertNull(PhoneDialerHelper.sanitizeDialablePhone("abc"))
        assertNull(PhoneDialerHelper.sanitizeDialablePhone("123"))

        val intent = PhoneDialerHelper.buildDialIntent("+91 98765 43210")
        assertNotNull(intent)
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertEquals("tel:+919876543210", intent?.data?.toString())
    }

    @Test
    fun `FloatingAssistantPositionMath clamps within safe bounds and snaps to nearest horizontal edge`() {
        val bounds = FloatingButtonBounds(minX = 12f, maxX = 320f, minY = 20f, maxY = 640f)

        val clampedOut = FloatingAssistantPositionMath.clamp(-50f, 900f, bounds)
        assertEquals(12f, clampedOut.x, 0.01f)
        assertEquals(640f, clampedOut.y, 0.01f)

        val snapLeft = FloatingAssistantPositionMath.snapToNearestHorizontalEdge(80f, 300f, bounds)
        assertEquals(12f, snapLeft.x, 0.01f)
        assertEquals(300f, snapLeft.y, 0.01f)

        val snapRight = FloatingAssistantPositionMath.snapToNearestHorizontalEdge(260f, 300f, bounds)
        assertEquals(320f, snapRight.x, 0.01f)
    }

    @Test
    fun `VoiceAssistantWidget opens assistant cleanly on normal tap`() {
        var opened = false
        composeRule.setContent {
            FoodFlowTheme {
                VoiceAssistantWidget(
                    uiState = VoiceAssistantUiState(isSheetOpen = false, sheetDetent = VoiceSheetDetent.HIDDEN),
                    visible = true,
                    bottomOffset = 80.dp,
                    snappedRight = true,
                    verticalFraction = null,
                    onPositionChanged = { _, _ -> },
                    onOpenAssistant = { opened = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_assistant_fab").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test
    fun `AssistantInputBar follows ChatGPT rule showing Mic when empty and Send only when text is entered`() {
        var draft by mutableStateOf("")
        var micClicks = 0
        var sendClicks = 0

        composeRule.setContent {
            FoodFlowTheme {
                AssistantInputBar(
                    text = draft,
                    isProcessing = false,
                    onTextChange = { draft = it },
                    onSubmit = { sendClicks++ },
                    onMicClick = { micClicks++ },
                    onStopClick = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Empty input -> Mic ONLY, Send hidden
        composeRule.onNodeWithTag("voice_mic_input_button").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_send_text_button").assertDoesNotExist()
        composeRule.onNodeWithTag("voice_mic_input_button").performClick()
        assertEquals(1, micClicks)

        // Non-empty text -> Send ONLY, Mic hidden
        draft = "Find veg biryani"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_send_text_button").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_mic_input_button").assertDoesNotExist()
        composeRule.onNodeWithTag("voice_send_text_button").performClick()
        assertEquals(1, sendClicks)
    }

    @Test
    fun `VoiceAssistantBottomSheet enters focused Voice Listening Mode hiding text input and showing ONLY Speak and Stop`() {
        val state = VoiceAssistantUiState(
            isSheetOpen = true,
            sheetDetent = VoiceSheetDetent.HALF_EXPANDED,
            phase = VoiceAssistantPhase.LISTENING,
            recordingState = VoiceRecordingState.LISTENING,
            inputAmplitude = 0.65f,
            statusText = "Listening to you..."
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

        composeRule.onNodeWithTag("voice_listening_mode_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_audio_visualizer").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_speak_button").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_stop_button").assertIsDisplayed()
        // Normal text-entry UI is hidden while in active Voice Listening Mode
        composeRule.onNodeWithTag("voice_text_input").assertDoesNotExist()
        // Forbidden controls must never exist
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
        composeRule.onNodeWithText("Resume").assertDoesNotExist()
        composeRule.onNodeWithText("Mute").assertDoesNotExist()
    }

    @Test
    fun `VoiceConversationStoreImpl guarantees chronological User-before-Assistant ordering even if tool response arrives first`() {
        val store = VoiceConversationStoreImpl()
        val turn1 = store.advanceTurnId()

        // Simulate out-of-order streaming arrival where tool/assistant chunk arrives BEFORE speech transcript
        store.updateStreamingMessage(
            turnId = turn1,
            role = VoiceMessageRole.TOOL_ACTION,
            fullText = "Here are top rated restaurants",
            isStreaming = false
        )
        store.updateStreamingMessage(
            turnId = turn1,
            role = VoiceMessageRole.USER,
            fullText = "Show top rated restaurants",
            isStreaming = false
        )

        val turn2 = store.advanceTurnId()
        store.updateStreamingMessage(
            turnId = turn2,
            role = VoiceMessageRole.USER,
            fullText = "Add paneer tikka to cart",
            isStreaming = false
        )
        store.updateStreamingMessage(
            turnId = turn2,
            role = VoiceMessageRole.ASSISTANT,
            fullText = "Added Paneer Tikka to your cart.",
            isStreaming = false
        )

        val messages = store.conversation.value.messages
        assertEquals(4, messages.size)
        assertEquals(VoiceMessageRole.USER, messages[0].role)
        assertEquals(turn1, messages[0].turnId)
        assertEquals(VoiceMessageRole.TOOL_ACTION, messages[1].role)
        assertEquals(turn1, messages[1].turnId)
        assertEquals(VoiceMessageRole.USER, messages[2].role)
        assertEquals(turn2, messages[2].turnId)
        assertEquals(VoiceMessageRole.ASSISTANT, messages[3].role)
        assertEquals(turn2, messages[3].turnId)
    }

    private fun sampleOrder(status: OrderStatus) = Order(
        id = "FF-TEST-1",
        userId = "u1",
        idempotencyKey = "idem_1",
        restaurantId = "r1",
        restaurantName = "Spice Route Kitchen",
        restaurantAddress = "SG Hwy",
        restaurantLat = 23.02,
        restaurantLng = 72.57,
        deliveryAddress = "Satellite",
        deliveryLat = 23.04,
        deliveryLng = 72.53,
        deliveryInstructions = "",
        items = emptyList(),
        subtotal = 320.0,
        deliveryFee = 30.0,
        packagingCharge = 12.0,
        taxes = 16.0,
        discount = 0.0,
        totalPayable = 378.0,
        couponCode = null,
        orderStatus = status,
        paymentStatus = PaymentStatus.SUCCESS,
        paymentMethod = PaymentMethod.UPI,
        estimatedDeliveryMinutes = 24,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )
}
