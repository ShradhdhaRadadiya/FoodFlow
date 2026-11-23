package com.foodflow.feature

import app.cash.turbine.test
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.invoice.InvoicePdfGenerator
import com.foodflow.core.location.DeliveryLocationProvider
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.CheckoutAttemptStatus
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.DiscountType
import com.foodflow.domain.model.InvoiceDocument
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.TrackingDiagnosticInfo
import com.foodflow.domain.repository.AddressRepository
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.DeliveryRouteRepository
import com.foodflow.domain.repository.OrderRepository
import com.foodflow.domain.repository.OrderTrackingRepository
import com.foodflow.domain.repository.RestaurantRepository
import com.foodflow.domain.repository.ReviewRepository
import com.foodflow.domain.repository.ScheduledOrderRepository
import com.foodflow.domain.repository.UserPreferencesRepository
import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.ValidateCouponUseCase
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.address.SaveAddressUseCase
import com.foodflow.domain.usecase.address.SelectAddressUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ClearCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.SyncPendingCartOperationsUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.cart.UpdateCookingInstructionsUseCase
import com.foodflow.domain.usecase.cart.ValidateCartUseCase
import com.foodflow.domain.usecase.order.CancelOrderUseCase
import com.foodflow.domain.usecase.order.GenerateOrderInvoiceUseCase
import com.foodflow.domain.usecase.order.ObserveActiveCheckoutAttemptUseCase
import com.foodflow.domain.usecase.order.ObserveOrderTimelineUseCase
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.order.RefreshOrdersUseCase
import com.foodflow.domain.usecase.order.ReorderUseCase
import com.foodflow.domain.usecase.order.StartOrResumeCheckoutUseCase
import com.foodflow.domain.usecase.order.SubmitIdempotentOrderUseCase
import com.foodflow.domain.usecase.preferences.ObserveUserPreferencesUseCase
import com.foodflow.domain.usecase.restaurant.ClearSearchHistoryUseCase
import com.foodflow.domain.usecase.restaurant.GetCouponByCodeUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCategoriesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCouponsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRecentSearchesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.SaveSearchQueryUseCase
import com.foodflow.domain.usecase.restaurant.SearchFoodItemsUseCase
import com.foodflow.domain.usecase.restaurant.SearchRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.review.SubmitRestaurantReviewUseCase
import com.foodflow.domain.usecase.review.UpdateRestaurantReviewUseCase
import com.foodflow.domain.usecase.scheduled.AcknowledgeScheduledFailureUseCase
import com.foodflow.domain.usecase.scheduled.BuildScheduledFailurePopupUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.CancelScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.CreateScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.ExecuteScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.domain.usecase.scheduled.GetRestaurantOperatingHoursUseCase
import com.foodflow.domain.usecase.scheduled.GetScheduledOrdersUseCase
import com.foodflow.domain.usecase.scheduled.ObserveScheduledFailureAlertsUseCase
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.scheduled.UpdateScheduledOrderUseCase
import com.foodflow.domain.usecase.tracking.AdvanceOrderTrackingStepUseCase
import com.foodflow.domain.usecase.tracking.DismissDeliveredOrderBannerUseCase
import com.foodflow.domain.usecase.tracking.EnsureActiveOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.GetDeliveryRouteUseCase
import com.foodflow.domain.usecase.tracking.ObserveActiveTrackedOrderUseCase
import com.foodflow.domain.usecase.tracking.ObserveDeliveryLocationUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingConnectionUseCase
import com.foodflow.domain.usecase.tracking.ObserveTrackingDiagnosticsUseCase
import com.foodflow.domain.usecase.tracking.ReconcileOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.ReconnectOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StartOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.StopOrderTrackingUseCase
import com.foodflow.feature.cart.CartEffect
import com.foodflow.feature.cart.CartIntent
import com.foodflow.feature.cart.CartViewModel
import com.foodflow.feature.checkout.CheckoutEffect
import com.foodflow.feature.checkout.CheckoutIntent
import com.foodflow.feature.checkout.CheckoutViewModel
import com.foodflow.feature.home.HomeViewModel
import com.foodflow.feature.orders.OrdersIntent
import com.foodflow.feature.orders.OrdersTab
import com.foodflow.feature.orders.OrdersViewModel
import com.foodflow.feature.search.SearchIntent
import com.foodflow.feature.search.SearchViewModel
import com.foodflow.feature.tracking.OrderTrackingIntent
import com.foodflow.feature.tracking.OrderTrackingViewModel
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelMviTests {

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
    fun `CartViewModel applies valid coupon and emits NavigateToCheckout effect`() = runTest(testDispatcher) {
        val cartRepo = mockk<CartRepository>(relaxed = true)
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val connectivity = FakeConnectivityObserver(true)

        val item = TestFixtures.cartItem(unitPrice = 300.0, quantity = 1)
        val coupon = TestFixtures.coupon(
            code = "FLOW50",
            type = DiscountType.PERCENTAGE,
            value = 50.0,
            minOrder = 199.0,
            maxDiscount = 100.0
        )

        every { cartRepo.observeCartItems() } returns flowOf(listOf(item))
        every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
        every { restaurantRepo.observeCoupons() } returns flowOf(listOf(coupon))
        every { restaurantRepo.observeMenu(any()) } returns flowOf(emptyList())
        coEvery { restaurantRepo.getCouponByCode("FLOW50") } returns coupon
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

        assertEquals(300.0, viewModel.state.value.breakdown.subtotal, 0.01)

        viewModel.effects.test {
            viewModel.onIntent(CartIntent.ApplyCouponCode("FLOW50"))
            advanceUntilIdle()

            val msgEffect = awaitItem()
            assertTrue(msgEffect is CartEffect.ShowMessage)
            assertEquals("FLOW50", viewModel.state.value.appliedCoupon?.code)
            assertEquals(100.0, viewModel.state.value.breakdown.discount, 0.01)

            viewModel.onIntent(CartIntent.ProceedToCheckout)
            advanceUntilIdle()

            val navEffect = awaitItem()
            assertEquals(CartEffect.NavigateToCheckout("FLOW50"), navEffect)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CheckoutViewModel blocks offline order placement and reuses persisted attempt online`() = runTest(testDispatcher) {
        val cartRepo = mockk<CartRepository>(relaxed = true)
        val addressRepo = mockk<AddressRepository>(relaxed = true)
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val prefsRepo = mockk<UserPreferencesRepository>(relaxed = true)
        val connectivity = FakeConnectivityObserver(initialOnline = false)

        val address = DeliveryAddress(
            id = "addr_1", label = AddressLabel.HOME, recipientName = "Aarav",
            line1 = "14 Satellite", landmark = "Near Mall", city = "Ahmedabad",
            pincode = "380015", deliveryInstructions = "", latitude = 23.02, longitude = 72.53,
            isDefault = true, isServiceable = true
        )
        val attemptFlow = MutableStateFlow<CheckoutAttempt?>(null)
        val createdAttempt = CheckoutAttempt(
            idempotencyKey = "idem_fixed_123", userId = "u1", restaurantId = "rest_1",
            addressId = "addr_1", paymentMethod = PaymentMethod.UPI, couponCode = null,
            totalAmount = 347.0, serverOrderId = null, paymentTransactionId = null,
            status = CheckoutAttemptStatus.IN_PROGRESS, createdAtMs = 1L, updatedAtMs = 1L
        )
        val createdOrder = sampleOrder(id = "FF-2026-9999", status = OrderStatus.PLACED)

        every { prefsRepo.preferences } returns flowOf(UserPreferences(preferredPaymentMethod = "UPI"))
        every { addressRepo.observeSelectedAddress() } returns flowOf(address)
        every { cartRepo.observeCartItems() } returns flowOf(listOf(TestFixtures.cartItem(unitPrice = 300.0)))
        every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
        every { restaurantRepo.observeCoupons() } returns flowOf(emptyList())
        every { orderRepo.observeActiveCheckoutAttempt() } returns attemptFlow
        coEvery { orderRepo.startOrResumeCheckout("addr_1", PaymentMethod.UPI, null) } returns AppResult.Success(createdAttempt)
        coEvery { orderRepo.submitIdempotentOrder(createdAttempt) } returns AppResult.Success(createdOrder)

        val scheduledRepo = mockk<ScheduledOrderRepository>(relaxed = true)
        coEvery { scheduledRepo.getRestaurantOperatingHours(any()) } returns RestaurantOperatingHours()

        val viewModel = CheckoutViewModel(
            observeCartItems = ObserveCartItemsUseCase(cartRepo),
            observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
            observeCoupons = ObserveCouponsUseCase(restaurantRepo),
            observeSelectedAddress = ObserveSelectedAddressUseCase(addressRepo),
            observeActiveCheckoutAttempt = ObserveActiveCheckoutAttemptUseCase(orderRepo),
            saveAddress = SaveAddressUseCase(addressRepo),
            selectAddress = SelectAddressUseCase(addressRepo),
            observeUserPreferences = ObserveUserPreferencesUseCase(prefsRepo),
            startOrResumeCheckout = StartOrResumeCheckoutUseCase(orderRepo),
            submitIdempotentOrder = SubmitIdempotentOrderUseCase(orderRepo),
            calculateCartTotals = CalculateCartTotalsUseCase(),
            createScheduledOrder = CreateScheduledOrderUseCase(scheduledRepo),
            calculateNextOccurrence = CalculateNextScheduleOccurrenceUseCase(),
            formatScheduledPlacement = FormatScheduledPlacementUseCase(),
            getRestaurantOperatingHours = GetRestaurantOperatingHoursUseCase(scheduledRepo),
            connectivityObserver = connectivity
        )
        advanceUntilIdle()

        viewModel.onIntent(CheckoutIntent.PlaceOrder)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.errorMessage)
        coVerify(exactly = 0) { orderRepo.submitIdempotentOrder(any()) }

        connectivity.setSimulatedOffline(false)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isOffline)

        viewModel.effects.test {
            viewModel.onIntent(CheckoutIntent.PlaceOrder)
            advanceUntilIdle()
            val navEffect = awaitItem()
            assertEquals(CheckoutEffect.NavigateToPayment("FF-2026-9999", "idem_fixed_123"), navEffect)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SearchViewModel debounces queries, filters pure veg, and saves submitted queries`() = runTest(testDispatcher) {
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val connectivity = FakeConnectivityObserver(true)

        val vegRest = TestFixtures.restaurant(id = "r_veg", name = "Green Leaf Thali", isPureVeg = true)
        val nonVegRest = TestFixtures.restaurant(id = "r_nv", name = "Royal Biryani", isPureVeg = false)

        every { restaurantRepo.observeRecentSearches() } returns flowOf(listOf("Biryani"))
        every { restaurantRepo.observeRestaurants() } returns flowOf(listOf(vegRest, nonVegRest))
        every { restaurantRepo.searchRestaurantsLocal("Thali") } returns flowOf(listOf(vegRest))
        every { restaurantRepo.searchFoodItemsLocal("Thali") } returns flowOf(emptyList())

        val viewModel = SearchViewModel(
            observeRecentSearches = ObserveRecentSearchesUseCase(restaurantRepo),
            observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
            searchRestaurants = SearchRestaurantsUseCase(restaurantRepo),
            searchFoodItems = SearchFoodItemsUseCase(restaurantRepo),
            saveSearchQuery = SaveSearchQueryUseCase(restaurantRepo),
            clearSearchHistory = ClearSearchHistoryUseCase(restaurantRepo),
            toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(restaurantRepo),
            connectivityObserver = connectivity
        )
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.filteredRestaurants.size)

        viewModel.onIntent(SearchIntent.TogglePureVeg)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.filteredRestaurants.size)
        assertEquals("r_veg", viewModel.state.value.filteredRestaurants.first().id)

        viewModel.onIntent(SearchIntent.QueryChanged("T"))
        advanceTimeBy(100L)
        viewModel.onIntent(SearchIntent.QueryChanged("Thali"))
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.matchingRestaurants.size)

        viewModel.onIntent(SearchIntent.SubmitSearch("Thali"))
        advanceUntilIdle()
        coVerify(exactly = 1) { restaurantRepo.saveSearchQuery("Thali") }
    }

    @Test
    fun `HomeViewModel shows no active order banner when only delivered orders exist`() = runTest(testDispatcher) {
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val addressRepo = mockk<AddressRepository>(relaxed = true)
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val trackingRepo = mockk<OrderTrackingRepository>(relaxed = true)
        val connectivity = FakeConnectivityObserver(true)

        val deliveredOrder = sampleOrder(id = "FF-2026-9001", status = OrderStatus.DELIVERED)
        every { restaurantRepo.observeCategories() } returns flowOf(emptyList())
        every { restaurantRepo.observeRestaurants() } returns flowOf(listOf(TestFixtures.restaurant()))
        every { restaurantRepo.observeFavoriteRestaurants() } returns flowOf(listOf(TestFixtures.restaurant().copy(isFavorite = true)))
        every { addressRepo.observeSelectedAddress() } returns flowOf(null)
        every { orderRepo.observeOrders() } returns flowOf(listOf(deliveredOrder))
        every { trackingRepo.activeTrackedOrder } returns MutableStateFlow(null)
        coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Success(1_000L)
        coEvery { orderRepo.refreshOrders() } returns AppResult.Success(Unit)

        val viewModel = HomeViewModel(
            observeCategories = ObserveCategoriesUseCase(restaurantRepo),
            observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
            observeFavoriteRestaurants = com.foodflow.domain.usecase.restaurant.ObserveFavoriteRestaurantsUseCase(restaurantRepo),
            observeSelectedAddress = ObserveSelectedAddressUseCase(addressRepo),
            observeOrders = ObserveOrdersUseCase(orderRepo),
            observeActiveTrackedOrder = ObserveActiveTrackedOrderUseCase(trackingRepo),
            ensureActiveOrderTracking = EnsureActiveOrderTrackingUseCase(trackingRepo),
            dismissDeliveredOrderBanner = DismissDeliveredOrderBannerUseCase(trackingRepo),
            refreshHomeCatalog = RefreshHomeCatalogUseCase(restaurantRepo),
            refreshOrders = RefreshOrdersUseCase(orderRepo),
            toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(restaurantRepo),
            connectivityObserver = connectivity
        )
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeOrder)
        assertNull(viewModel.state.value.deliveredOrderBanner)
        assertEquals(1, viewModel.state.value.favoriteCount)
        assertTrue(viewModel.state.value.promoBanners.size >= 4)
        coVerify(exactly = 1) { restaurantRepo.refreshHomeCatalog() }
    }

    @Test
    fun `OrdersViewModel defaults to Completed tab when no active orders and generates PDF invoice`() = runTest(testDispatcher) {
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val prefsRepo = mockk<UserPreferencesRepository>(relaxed = true)
        val pdfGenerator = mockk<InvoicePdfGenerator>()
        val connectivity = FakeConnectivityObserver(true)

        val delivered = sampleOrder(id = "FF-PAST-1", status = OrderStatus.DELIVERED)
        val expectedInvoice = InvoiceDocument(
            orderId = "FF-PAST-1",
            fileName = "FoodFlow_Invoice_FF-PAST-1.pdf",
            uriString = "content://downloads/FoodFlow_Invoice_FF-PAST-1.pdf",
            savedLocationLabel = "Saved to Downloads/FoodFlow/FoodFlow_Invoice_FF-PAST-1.pdf"
        )

        every { orderRepo.observeOrders() } returns flowOf(listOf(delivered))
        every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
        every { prefsRepo.preferences } returns flowOf(UserPreferences(userName = "Aarav", userPhone = "+91 9876543210"))
        coEvery { orderRepo.refreshOrders() } returns AppResult.Success(Unit)
        coEvery { pdfGenerator.generateAndSaveInvoice(delivered, "Aarav", "+91 9876543210") } returns AppResult.Success(expectedInvoice)

        val reviewRepo = mockk<ReviewRepository>(relaxed = true)
        val scheduledRepo = mockk<ScheduledOrderRepository>(relaxed = true)
        every { scheduledRepo.observeScheduledOrders() } returns flowOf(emptyList())
        every { scheduledRepo.observeUnacknowledgedFailedSchedules() } returns flowOf(emptyList())
        coEvery {
            reviewRepo.submitReview("FF-PAST-1", 5, "Awesome!")
        } returns AppResult.Success(RestaurantRatingSummary("rest_1", 4.21, 101))

        val formatPlacement = FormatScheduledPlacementUseCase()
        val viewModel = OrdersViewModel(
            observeOrders = ObserveOrdersUseCase(orderRepo),
            observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
            getScheduledOrders = GetScheduledOrdersUseCase(scheduledRepo),
            observeScheduledFailureAlerts = ObserveScheduledFailureAlertsUseCase(
                scheduledRepo,
                BuildScheduledFailurePopupUseCase()
            ),
            observeUserPreferences = ObserveUserPreferencesUseCase(prefsRepo),
            refreshOrders = RefreshOrdersUseCase(orderRepo),
            reorderUseCase = ReorderUseCase(orderRepo),
            submitRestaurantReview = SubmitRestaurantReviewUseCase(reviewRepo),
            updateRestaurantReview = UpdateRestaurantReviewUseCase(reviewRepo),
            generateOrderInvoice = GenerateOrderInvoiceUseCase(pdfGenerator),
            cancelScheduledOrder = CancelScheduledOrderUseCase(scheduledRepo),
            updateScheduledOrder = UpdateScheduledOrderUseCase(scheduledRepo),
            executeScheduledOrder = ExecuteScheduledOrderUseCase(scheduledRepo),
            acknowledgeScheduledFailure = AcknowledgeScheduledFailureUseCase(scheduledRepo),
            reconcileOverdueSchedules = ReconcileOverdueSchedulesUseCase(scheduledRepo),
            calculateNextOccurrence = CalculateNextScheduleOccurrenceUseCase(),
            formatScheduledPlacement = formatPlacement,
            connectivityObserver = connectivity
        )
        advanceUntilIdle()

        assertEquals(OrdersTab.COMPLETED, viewModel.state.value.selectedTab)
        assertEquals(1, viewModel.state.value.completedOrders.size)

        viewModel.onIntent(OrdersIntent.DownloadInvoice(delivered))
        advanceUntilIdle()

        assertEquals(expectedInvoice, viewModel.state.value.readyInvoice)

        viewModel.onIntent(OrdersIntent.OpenRatingDialog("FF-PAST-1"))
        viewModel.onIntent(OrdersIntent.SubmitRating("FF-PAST-1", 5, "Awesome!"))
        advanceUntilIdle()
        coVerify(exactly = 1) { reviewRepo.submitReview("FF-PAST-1", 5, "Awesome!") }
    }

    @Test
    fun `OrderTrackingViewModel observes live order updates cleanly without Activity Log state`() = runTest(testDispatcher) {
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val trackingRepo = mockk<OrderTrackingRepository>(relaxed = true)
        val routeRepo = mockk<DeliveryRouteRepository>(relaxed = true)
        val locationProvider = mockk<DeliveryLocationProvider>(relaxed = true)
        val connectivity = FakeConnectivityObserver(true)

        every { orderRepo.observeOrder("FF-1") } returns flowOf(sampleOrder("FF-1", OrderStatus.PREPARING))
        every { orderRepo.observeOrderTimeline("FF-1") } returns flowOf(emptyList())
        every { locationProvider.observeDeliveryLocation("FF-1") } returns flowOf(null)
        every { trackingRepo.connectionState } returns MutableStateFlow(WebSocketConnectionState.CONNECTED)
        every { trackingRepo.diagnostics } returns MutableStateFlow(TrackingDiagnosticInfo())
        every { trackingRepo.activeTrackedOrder } returns MutableStateFlow(null)

        val viewModel = OrderTrackingViewModel(
            observeOrder = ObserveOrderUseCase(orderRepo),
            observeActiveTrackedOrder = ObserveActiveTrackedOrderUseCase(trackingRepo),
            observeOrderTimeline = ObserveOrderTimelineUseCase(orderRepo),
            observeDeliveryLocation = ObserveDeliveryLocationUseCase(locationProvider),
            observeTrackingConnection = ObserveTrackingConnectionUseCase(trackingRepo),
            observeTrackingDiagnostics = ObserveTrackingDiagnosticsUseCase(trackingRepo),
            startOrderTracking = StartOrderTrackingUseCase(trackingRepo),
            stopOrderTracking = StopOrderTrackingUseCase(trackingRepo),
            reconnectOrderTracking = ReconnectOrderTrackingUseCase(trackingRepo),
            reconcileOrderTracking = ReconcileOrderTrackingUseCase(trackingRepo),
            getDeliveryRoute = GetDeliveryRouteUseCase(routeRepo),
            cancelOrderUseCase = CancelOrderUseCase(orderRepo),
            connectivityObserver = connectivity
        )

        viewModel.onIntent(OrderTrackingIntent.Start("FF-1"))
        advanceUntilIdle()
        assertEquals(OrderStatus.PREPARING, viewModel.state.value.order?.orderStatus)
    }

    private fun sampleOrder(id: String, status: OrderStatus) = Order(
        id = id,
        userId = "u1",
        idempotencyKey = "idem_$id",
        restaurantId = "rest_1",
        restaurantName = "Spice Route",
        restaurantAddress = "SG Hwy",
        restaurantLat = 23.02,
        restaurantLng = 72.57,
        deliveryAddress = "14 Satellite",
        deliveryLat = 23.03,
        deliveryLng = 72.53,
        deliveryInstructions = "",
        items = emptyList(),
        subtotal = 300.0,
        deliveryFee = 35.0,
        packagingCharge = 12.0,
        taxes = 15.0,
        discount = 0.0,
        totalPayable = 362.0,
        couponCode = null,
        orderStatus = status,
        paymentStatus = PaymentStatus.SUCCESS,
        paymentMethod = PaymentMethod.UPI,
        estimatedDeliveryMinutes = 25,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )
}
