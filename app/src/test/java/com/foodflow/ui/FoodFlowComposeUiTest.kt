package com.foodflow.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.foodflow.core.designsystem.components.AnimatedEmptyCartIllustration
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.OrderStatusFeedbackCard
import com.foodflow.core.designsystem.components.PriceBreakdownCard
import com.foodflow.core.designsystem.components.QuantityStepper
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.core.designsystem.components.resolveOrderStatusAnimation
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.testutil.TestFixtures
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FoodFlowComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `OfflineBanner displays clean offline copy without Pending Sync technical jargon`() {
        composeRule.setContent {
            FoodFlowTheme {
                Column {
                    OfflineBanner(isOffline = true)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("offline_status_banner").assertIsDisplayed()
        composeRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Pending Sync", substring = true).assertDoesNotExist()
    }

    @Test
    fun `FoodFlowTopBar renders unified header title and back action`() {
        var backClicked = false
        composeRule.setContent {
            FoodFlowTheme {
                FoodFlowTopBar(
                    title = "Your Orders",
                    subtitle = "Track live or past meals",
                    onBack = { backClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Your Orders").assertIsDisplayed()
        composeRule.onNodeWithText("Track live or past meals").assertIsDisplayed()
        composeRule.onNodeWithTag("top_bar_back_button").performClick()
        assertTrue(backClicked)
    }

    @Test
    fun `RestaurantCard renders highlight badge, details, and triggers click callbacks`() {
        var opened = false
        var toggledFavorite = false
        val restaurant = TestFixtures.restaurant(id = "r_spice", name = "Spice Route Kitchen")

        composeRule.setContent {
            FoodFlowTheme {
                RestaurantCard(
                    restaurant = restaurant,
                    onClick = { opened = true },
                    onToggleFavorite = { toggledFavorite = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Spice Route Kitchen").assertIsDisplayed()
        composeRule.onNodeWithText("50% OFF up to ₹100").assertIsDisplayed()

        composeRule.onNodeWithTag("fav_toggle_r_spice").performClick()
        assertTrue(toggledFavorite)

        composeRule.onNodeWithTag("restaurant_card_r_spice").performClick()
        assertTrue(opened)
    }

    @Test
    fun `AnimatedEmptyCartIllustration shows Explore Restaurants CTA and triggers callback`() {
        var exploreClicked = false
        composeRule.setContent {
            FoodFlowTheme {
                AnimatedEmptyCartIllustration(
                    onExploreRestaurants = { exploreClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Your cart is empty").assertIsDisplayed()
        composeRule.onNodeWithText("Explore Restaurants").assertIsDisplayed()
        composeRule.onNodeWithTag("empty_state_action").performClick()
        assertTrue(exploreClicked)
    }

    @Test
    fun `QuantityStepper increments and decrements reactive count`() {
        composeRule.setContent {
            FoodFlowTheme {
                var qty by remember { mutableIntStateOf(1) }
                QuantityStepper(
                    quantity = qty,
                    onIncrement = { qty += 1 },
                    onDecrement = { qty = (qty - 1).coerceAtLeast(0) },
                    itemTagPrefix = "biryani"
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("biryani_value").assertTextEquals("1")
        composeRule.onNodeWithTag("biryani_increment").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("biryani_value").assertTextEquals("2")

        composeRule.onNodeWithTag("biryani_decrement").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("biryani_value").assertTextEquals("1")
    }

    @Test
    fun `multiple QuantityStepper rows keep independent counts when incrementing first item`() {
        composeRule.setContent {
            FoodFlowTheme {
                var qty1 by remember { mutableIntStateOf(1) }
                var qty2 by remember { mutableIntStateOf(1) }
                var qty3 by remember { mutableIntStateOf(1) }
                Column {
                    QuantityStepper(
                        quantity = qty1,
                        onIncrement = { qty1 += 1 },
                        onDecrement = { qty1 = (qty1 - 1).coerceAtLeast(0) },
                        itemTagPrefix = "item_1",
                        itemKey = "item_1"
                    )
                    QuantityStepper(
                        quantity = qty2,
                        onIncrement = { qty2 += 1 },
                        onDecrement = { qty2 = (qty2 - 1).coerceAtLeast(0) },
                        itemTagPrefix = "item_2",
                        itemKey = "item_2"
                    )
                    QuantityStepper(
                        quantity = qty3,
                        onIncrement = { qty3 += 1 },
                        onDecrement = { qty3 = (qty3 - 1).coerceAtLeast(0) },
                        itemTagPrefix = "item_3",
                        itemKey = "item_3"
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("item_1_increment").performClick()
        composeRule.onNodeWithTag("item_1_increment").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("item_1_value").assertTextEquals("3")
        composeRule.onNodeWithTag("item_2_value").assertTextEquals("1")
        composeRule.onNodeWithTag("item_3_value").assertTextEquals("1")
    }

    @Test
    fun `PriceBreakdownCard displays accurate bill details without sync badges`() {
        val breakdown = CartPriceBreakdown(
            subtotal = 400.0,
            deliveryFee = 0.0,
            packagingCharge = 24.0,
            taxes = 15.0,
            discount = 100.0,
            totalPayable = 339.0,
            appliedCouponCode = "FLOW50"
        )

        composeRule.setContent {
            FoodFlowTheme {
                PriceBreakdownCard(breakdown = breakdown)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bill Details").assertIsDisplayed()
        composeRule.onNodeWithText("Coupon Discount (FLOW50)").assertIsDisplayed()
        composeRule.onNodeWithText("FREE").assertIsDisplayed()
        composeRule.onNodeWithText("Pending Sync").assertDoesNotExist()
    }

    @Test
    fun `OrderStatusFeedbackCard renders Lottie animation and updates smoothly across status transitions`() {
        val baseOrder = Order(
            id = "FF-LOTTIE-1",
            userId = "u1",
            idempotencyKey = "idem_lottie_1",
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
            orderStatus = OrderStatus.PLACED,
            paymentStatus = PaymentStatus.SUCCESS,
            paymentMethod = PaymentMethod.UPI,
            estimatedDeliveryMinutes = 24,
            createdAtMs = 1L,
            updatedAtMs = 1L
        )

        OrderStatus.entries.forEach { status ->
            val spec = resolveOrderStatusAnimation(status)
            assertTrue(spec.rawRes != 0)
            assertTrue(spec.statusHint.isNotBlank())
        }

        val currentStatus = mutableStateOf(OrderStatus.PLACED)
        composeRule.setContent {
            FoodFlowTheme {
                OrderStatusFeedbackCard(
                    order = baseOrder.copy(orderStatus = currentStatus.value),
                    onCancelOrder = { currentStatus.value = OrderStatus.CANCELLED }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tracking_advance_step_button").assertDoesNotExist()
        composeRule.onNodeWithTag("order_status_lottie_placed").assertIsDisplayed()
        composeRule.onNodeWithText("Order Placed").assertIsDisplayed()

        composeRule.runOnUiThread { currentStatus.value = OrderStatus.PREPARING }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("order_status_lottie_preparing").assertIsDisplayed()
        composeRule.onNodeWithText("Food Preparing").assertIsDisplayed()

        composeRule.runOnUiThread { currentStatus.value = OrderStatus.PICKED_UP }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("order_status_lottie_picked_up").assertIsDisplayed()
        composeRule.onNodeWithText("Picked Up").assertIsDisplayed()

        composeRule.runOnUiThread { currentStatus.value = OrderStatus.DELIVERED }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("order_status_lottie_delivered").assertIsDisplayed()
        composeRule.onNodeWithText("Delivered").assertIsDisplayed()
    }
}
