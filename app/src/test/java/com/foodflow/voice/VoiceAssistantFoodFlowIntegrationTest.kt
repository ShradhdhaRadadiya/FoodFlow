package com.foodflow.voice

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.local.paging.LocalCatalogPagingDataSource
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.repository.CartRepositoryImpl
import com.foodflow.data.repository.OrderRepositoryImpl
import com.foodflow.data.repository.RestaurantRepositoryImpl
import com.foodflow.data.voice.FoodFlowAssistantActionExecutorImpl
import com.foodflow.data.voice.RuleBasedFoodAssistantFallback
import com.foodflow.data.voice.VoiceConversationStoreImpl
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.SearchFoodItemsUseCase
import com.foodflow.domain.usecase.restaurant.SearchRestaurantsUseCase
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceAssistantFoodFlowIntegrationTest {

    private lateinit var db: FoodFlowDatabase
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var scenarioController: MockScenarioController
    private lateinit var mockBackend: LocalMockBackend
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var restaurantRepository: RestaurantRepositoryImpl
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var actionExecutor: FoodFlowAssistantActionExecutorImpl
    private lateinit var fallbackEngine: RuleBasedFoodAssistantFallback
    private lateinit var conversationStore: VoiceConversationStoreImpl
    private lateinit var addToCartUseCase: AddToCartUseCase

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        connectivity = FakeConnectivityObserver(initialOnline = true)
        scenarioController = MockScenarioController(connectivity).apply { setLatencyMs(0L) }

        val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
        val defaultPrefs = UserPreferences(
            userId = "voice_user_1",
            userName = "Priya Patel",
            authToken = "tok_voice_test"
        )
        every { prefs.preferences } returns flowOf(defaultPrefs)
        coEvery { prefs.getSnapshot() } returns defaultPrefs

        val seeder = mockk<DatabaseSeeder>(relaxed = true)
        coEvery { seeder.loadSeedDataset() } returns null

        val workManager = mockk<WorkManager>(relaxed = true)

        mockBackend = LocalMockBackend(
            connectivityObserver = connectivity,
            scenarioController = scenarioController,
            seeder = seeder,
            catalogDao = db.catalogDao(),
            serverStateDao = db.mockServerStateDao(),
            json = json
        )

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

        restaurantRepository = RestaurantRepositoryImpl(
            catalogDao = db.catalogDao(),
            favoriteDao = db.favoriteDao(),
            searchHistoryDao = db.searchHistoryDao(),
            pendingOperationDao = db.pendingOperationDao(),
            restaurantRemote = mockBackend,
            userRemote = mockBackend,
            preferencesDataStore = prefs,
            cartRepository = cartRepository,
            localPagingDataSource = LocalCatalogPagingDataSource(
                catalogDao = db.catalogDao(),
                favoriteDao = db.favoriteDao(),
                json = json,
                dispatchers = dispatchers
            ),
            json = json,
            dispatchers = dispatchers
        )

        val calculateCartTotals = CalculateCartTotalsUseCase()
        orderRepository = OrderRepositoryImpl(
            orderDao = db.orderDao(),
            cartDao = db.cartDao(),
            catalogDao = db.catalogDao(),
            addressDao = db.addressDao(),
            orderRemote = mockBackend,
            cartRepository = cartRepository,
            calculateCartTotals = calculateCartTotals,
            connectivityObserver = connectivity,
            preferencesDataStore = prefs,
            json = json,
            dispatchers = dispatchers
        )

        addToCartUseCase = AddToCartUseCase(cartRepository)
        actionExecutor = FoodFlowAssistantActionExecutorImpl(
            observeRestaurants = ObserveRestaurantsUseCase(restaurantRepository),
            searchRestaurants = SearchRestaurantsUseCase(restaurantRepository),
            searchFoodItems = SearchFoodItemsUseCase(restaurantRepository),
            observeMenu = ObserveMenuUseCase(restaurantRepository),
            observeCartItems = ObserveCartItemsUseCase(cartRepository),
            addToCart = addToCartUseCase,
            updateCartQuantity = UpdateCartQuantityUseCase(cartRepository),
            removeCartItem = RemoveCartItemUseCase(cartRepository),
            observeOrders = ObserveOrdersUseCase(orderRepository),
            calculateCartTotals = calculateCartTotals
        )
        fallbackEngine = RuleBasedFoodAssistantFallback(actionExecutor)
        conversationStore = VoiceConversationStoreImpl()

        seedCatalogAndActiveOrder()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `request vegetarian food under 200 returns filtered real Room items and adds second recommendation to cart`() =
        runTest(testDispatcher) {
            val (searchResult, fallbackReply) = fallbackEngine.handleFallbackQuery(
                userQuery = "Find vegetarian food under ₹200",
                context = conversationStore.conversation.value
            )

            assertTrue(searchResult.success)
            assertTrue(fallbackReply.isNotBlank())
            assertFalse(fallbackReply.contains("Local FoodFlow Assistant"))
            assertTrue(searchResult.recommendations.size >= 2)
            assertTrue(searchResult.recommendations.all { it.isVeg && it.price <= 200.0 })

            conversationStore.updateRecommendations(searchResult.recommendations)
            val secondItem = searchResult.recommendations[1]

            // Follow-up command referencing the second recommended item
            val (addResult, _) = fallbackEngine.handleFallbackQuery(
                userQuery = "Add the second one to my cart",
                context = conversationStore.conversation.value
            )

            assertTrue(addResult.success)
            val cartItems = cartRepository.observeCartItems().first()
            assertEquals(1, cartItems.size)
            assertEquals(secondItem.foodId, cartItems.first().itemId)
            assertEquals(1, cartItems.first().quantity)

            // Ask for cart summary using real CalculateCartTotalsUseCase
            val summaryResult = actionExecutor.execute(
                action = VoiceActionRequest.GetCartSummary,
                context = conversationStore.conversation.value
            )
            assertTrue(summaryResult.success)
            assertTrue(summaryResult.summaryForVoice.contains(secondItem.foodName))
            assertEquals("1", summaryResult.structuredPayload["itemCount"])
        }

    @Test
    fun `adding item from different restaurant requires explicit user confirmation before replacing cart`() =
        runTest(testDispatcher) {
            // Add item from rest_spice first
            val firstAdd = actionExecutor.execute(
                action = VoiceActionRequest.AddItemToCart(foodId = "food_paneer_wrap", quantity = 1),
                context = conversationStore.conversation.value
            )
            assertTrue(firstAdd.success)

            // Attempt adding pizza from rest_pizza without replaceExistingCart=true
            val crossRestaurantAttempt = actionExecutor.execute(
                action = VoiceActionRequest.AddItemToCart(foodId = "food_margherita", quantity = 1),
                context = conversationStore.conversation.value
            )

            assertNotNull(
                "Cross-restaurant cart replacement MUST require user confirmation",
                crossRestaurantAttempt.requiresConfirmation
            )
            // Original cart MUST remain untouched until confirmed
            val unchangedCart = cartRepository.observeCartItems().first()
            assertEquals("food_paneer_wrap", unchangedCart.first().itemId)

            // Execute confirmed replacement action
            val confirmedAction = crossRestaurantAttempt.requiresConfirmation!!.action
            val confirmedResult = actionExecutor.execute(
                action = confirmedAction,
                context = conversationStore.conversation.value
            )
            assertTrue(confirmedResult.success)
            val replacedCart = cartRepository.observeCartItems().first()
            assertEquals(1, replacedCart.size)
            assertEquals("food_margherita", replacedCart.first().itemId)
        }

    @Test
    fun `navigate to restaurant details and check live order status work with real FoodFlow data`() =
        runTest(testDispatcher) {
            val navResult = actionExecutor.execute(
                action = VoiceActionRequest.NavigateToScreen(
                    destination = "restaurant_detail",
                    restaurantName = "Napoli Oven"
                ),
                context = conversationStore.conversation.value
            )
            assertTrue(navResult.success)
            assertEquals(
                VoiceNavigationTarget.RestaurantDetail("rest_pizza"),
                navResult.navigationTarget
            )

            val orderStatusResult = actionExecutor.execute(
                action = VoiceActionRequest.GetOrderStatus(),
                context = conversationStore.conversation.value
            )
            assertTrue(orderStatusResult.success)
            assertEquals("ORD-VOICE-99", orderStatusResult.structuredPayload["orderId"])
            assertTrue(orderStatusResult.summaryForVoice.contains("Spice Route Kitchen"))
            assertTrue(orderStatusResult.summaryForVoice.contains("Picked Up"))

            val trackNavResult = actionExecutor.execute(
                action = VoiceActionRequest.NavigateToScreen(destination = "track_order"),
                context = conversationStore.conversation.value
            )
            assertTrue(trackNavResult.success)
            assertEquals(
                VoiceNavigationTarget.OrderTracking("ORD-VOICE-99"),
                trackNavResult.navigationTarget
            )
        }

    @Test
    fun `existing manual cart operations remain synchronized with voice assistant cart actions`() =
        runTest(testDispatcher) {
            val spiceRest = restaurantRepository.observeRestaurants().first().first { it.id == "rest_spice" }
            val paneerWrap = restaurantRepository.observeMenu("rest_spice").first().first { it.id == "food_paneer_wrap" }

            // User adds manually via existing AddToCartUseCase
            addToCartUseCase(restaurant = spiceRest, foodItem = paneerWrap)
            assertEquals(1, cartRepository.observeCartItems().first().sumOf { it.quantity })

            // Voice updates quantity to 3
            val updateRes = actionExecutor.execute(
                action = VoiceActionRequest.UpdateCartQuantity(foodName = "Paneer Tikka Wrap", quantity = 3),
                context = conversationStore.conversation.value
            )
            assertTrue(updateRes.success)
            assertEquals(3, cartRepository.observeCartItems().first().first().quantity)

            // Voice removes item cleanly
            val removeRes = actionExecutor.execute(
                action = VoiceActionRequest.RemoveCartItem(foodName = "Paneer Tikka Wrap"),
                context = conversationStore.conversation.value
            )
            assertTrue(removeRes.success)
            assertTrue(cartRepository.observeCartItems().first().isEmpty())
        }

    @Test
    fun `structured responses populate real deduplicated Room restaurants, food items, and cart summary`() =
        runTest(testDispatcher) {
            val restResult = actionExecutor.execute(
                action = VoiceActionRequest.SearchRestaurants(query = ""),
                context = conversationStore.conversation.value
            )
            val restStructured = restResult.structuredResponse
            assertNotNull(restStructured)
            assertEquals(VoiceResponseType.RESTAURANT_RECOMMENDATIONS, restStructured!!.type)
            assertEquals(2, restStructured.restaurants.size)
            assertEquals(
                restStructured.restaurants.size,
                restStructured.restaurants.map { it.id }.distinct().size
            )
            assertTrue(restStructured.headline.isNotBlank())

            val foodResult = actionExecutor.execute(
                action = VoiceActionRequest.SearchFoodItems(vegOnly = true, maxPrice = 200.0),
                context = conversationStore.conversation.value
            )
            val foodStructured = foodResult.structuredResponse
            assertNotNull(foodStructured)
            assertEquals(VoiceResponseType.FOOD_RECOMMENDATIONS, foodStructured!!.type)
            assertTrue(foodStructured.foodItems.size >= 2)
            assertTrue(foodStructured.foodItems.all { it.isVeg && it.price <= 200.0 })
            assertTrue(foodStructured.foodRestaurantNames.containsKey("rest_spice"))

            // Add item and verify structured CART_SUMMARY
            actionExecutor.execute(
                action = VoiceActionRequest.AddItemToCart(foodId = "food_paneer_wrap", quantity = 2),
                context = conversationStore.conversation.value
            )
            val cartResult = actionExecutor.execute(
                action = VoiceActionRequest.GetCartSummary,
                context = conversationStore.conversation.value
            )
            val cartStructured = cartResult.structuredResponse
            assertNotNull(cartStructured)
            assertEquals(VoiceResponseType.CART_SUMMARY, cartStructured!!.type)
            val cartSummary = cartStructured.cartSummary
            assertNotNull(cartSummary)
            assertEquals(2, cartSummary!!.itemCount)
            assertEquals("Paneer Tikka Wrap", cartSummary.items.first().foodName)
        }

    private suspend fun seedCatalogAndActiveOrder() {
        val now = System.currentTimeMillis()
        db.catalogDao().upsertRestaurants(
            listOf(
                RestaurantEntity(
                    id = "rest_spice",
                    name = "Spice Route Kitchen",
                    cuisinesCsv = "North Indian,Biryani",
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
                    imageKey = "indian",
                    address = "SG Highway, Ahmedabad",
                    distanceKm = 2.1,
                    latitude = 23.0225,
                    longitude = 72.5714,
                    lastUpdatedMs = now
                ),
                RestaurantEntity(
                    id = "rest_pizza",
                    name = "Napoli Oven",
                    cuisinesCsv = "Italian,Pizza",
                    rating = 4.7,
                    ratingCount = 310,
                    deliveryTimeMin = 22,
                    deliveryFee = 25.0,
                    minOrderAmount = 149.0,
                    offerText = "Free Delivery",
                    isPureVeg = true,
                    isOpen = true,
                    isPopular = true,
                    isRecommended = true,
                    imageKey = "pizza",
                    address = "Navrangpura, Ahmedabad",
                    distanceKm = 1.8,
                    latitude = 23.0365,
                    longitude = 72.5611,
                    lastUpdatedMs = now
                )
            )
        )

        db.catalogDao().upsertFoodItems(
            listOf(
                FoodItemEntity(
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
                    addonsJson = "[]",
                    spiceLevel = "Medium"
                ),
                FoodItemEntity(
                    id = "food_veg_pulao",
                    restaurantId = "rest_spice",
                    name = "Spicy Tawa Veg Pulao",
                    description = "Fragrant spiced vegetable rice tossed with butter",
                    categoryName = "Rice",
                    price = 185.0,
                    originalPrice = null,
                    isVeg = true,
                    isAvailable = true,
                    isRecommended = true,
                    isBestseller = false,
                    rating = 4.5,
                    imageKey = "indian",
                    addonsJson = "[]",
                    spiceLevel = "Spicy"
                ),
                FoodItemEntity(
                    id = "food_chicken_biryani",
                    restaurantId = "rest_spice",
                    name = "Chicken Dum Biryani",
                    description = "Slow-cooked spiced chicken biryani",
                    categoryName = "Biryani",
                    price = 290.0,
                    originalPrice = null,
                    isVeg = false,
                    isAvailable = true,
                    isRecommended = true,
                    isBestseller = true,
                    rating = 4.8,
                    imageKey = "indian",
                    addonsJson = "[]",
                    spiceLevel = "Spicy"
                ),
                FoodItemEntity(
                    id = "food_margherita",
                    restaurantId = "rest_pizza",
                    name = "Wood-Fired Margherita Pizza",
                    description = "Fresh mozzarella, basil, and San Marzano tomato sauce",
                    categoryName = "Pizza",
                    price = 240.0,
                    originalPrice = null,
                    isVeg = true,
                    isAvailable = true,
                    isRecommended = true,
                    isBestseller = true,
                    rating = 4.7,
                    imageKey = "pizza",
                    addonsJson = "[]",
                    spiceLevel = "Mild"
                )
            )
        )

        db.orderDao().upsertOrder(
            OrderEntity(
                id = "ORD-VOICE-99",
                userId = "voice_user_1",
                idempotencyKey = "idem_voice_99",
                restaurantId = "rest_spice",
                restaurantName = "Spice Route Kitchen",
                restaurantAddress = "SG Highway",
                restaurantLat = 23.0225,
                restaurantLng = 72.5714,
                deliveryAddress = "Bodakdev, Ahmedabad",
                deliveryLat = 23.0400,
                deliveryLng = 72.5100,
                deliveryInstructions = "Ring bell",
                itemsJson = "[]",
                subtotal = 185.0,
                deliveryFee = 30.0,
                packagingCharge = 12.0,
                taxes = 9.25,
                discount = 0.0,
                totalPayable = 236.25,
                couponCode = null,
                orderStatus = OrderStatus.PICKED_UP.name,
                paymentStatus = PaymentStatus.SUCCESS.name,
                paymentMethod = PaymentMethod.UPI.name,
                estimatedDeliveryMinutes = 14,
                createdAtMs = now - 600_000L,
                updatedAtMs = now
            )
        )
    }
}
