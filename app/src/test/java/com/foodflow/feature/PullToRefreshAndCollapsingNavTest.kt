package com.foodflow.feature

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import app.cash.turbine.test
import com.foodflow.app.navigation.BottomNavScrollState
import com.foodflow.app.navigation.FloatingCapsuleBottomNavBar
import com.foodflow.app.navigation.FoodFlowTopLevelDestinations
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.core.invoice.InvoicePdfGenerator
import com.foodflow.core.location.DeliveryLocationProvider
import com.foodflow.core.websocket.WebSocketConnectionState
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.RestaurantRatingSummary
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
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.order.CancelOrderUseCase
import com.foodflow.domain.usecase.order.GenerateOrderInvoiceUseCase
import com.foodflow.domain.usecase.order.ObserveOrderTimelineUseCase
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.order.RefreshOrdersUseCase
import com.foodflow.domain.usecase.order.ReorderUseCase
import com.foodflow.domain.usecase.preferences.ObserveUserPreferencesUseCase
import com.foodflow.domain.usecase.restaurant.GetPaginatedRestaurantMenuUseCase
import com.foodflow.domain.usecase.restaurant.GetPaginatedRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCategoriesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveFavoriteRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.RefreshRestaurantMenuUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.review.GetRestaurantReviewsUseCase
import com.foodflow.domain.usecase.review.RefreshRestaurantReviewsUseCase
import com.foodflow.domain.usecase.review.SubmitRestaurantReviewUseCase
import com.foodflow.domain.usecase.review.UpdateRestaurantReviewUseCase
import com.foodflow.domain.usecase.scheduled.AcknowledgeScheduledFailureUseCase
import com.foodflow.domain.usecase.scheduled.BuildScheduledFailurePopupUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.CancelScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.ExecuteScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.domain.usecase.scheduled.GetScheduledOrdersUseCase
import com.foodflow.domain.usecase.scheduled.ObserveScheduledFailureAlertsUseCase
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.scheduled.UpdateScheduledOrderUseCase
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
import com.foodflow.feature.favorites.FavoritesEffect
import com.foodflow.feature.favorites.FavoritesIntent
import com.foodflow.feature.favorites.FavoritesViewModel
import com.foodflow.feature.home.HomeEffect
import com.foodflow.feature.home.HomeIntent
import com.foodflow.feature.home.HomeScreenContent
import com.foodflow.feature.home.HomeUiState
import com.foodflow.feature.home.HomeViewModel
import com.foodflow.feature.orders.OrdersEffect
import com.foodflow.feature.orders.OrdersIntent
import com.foodflow.feature.orders.OrdersViewModel
import com.foodflow.feature.restaurant.RestaurantDetailEffect
import com.foodflow.feature.restaurant.RestaurantDetailIntent
import com.foodflow.feature.restaurant.RestaurantDetailViewModel
import com.foodflow.feature.restaurant.RestaurantListEffect
import com.foodflow.feature.restaurant.RestaurantListIntent
import com.foodflow.feature.restaurant.RestaurantListViewModel
import com.foodflow.feature.restaurant.RestaurantSort
import com.foodflow.feature.tracking.OrderTrackingEffect
import com.foodflow.feature.tracking.OrderTrackingIntent
import com.foodflow.feature.tracking.OrderTrackingViewModel
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
@Config(sdk = [36], application = Application::class)
class PullToRefreshAndCollapsingNavTest {

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

    // -------------------------------------------------------------------------
    // Pull-to-Refresh ViewModel Tests
    // -------------------------------------------------------------------------

    @Test
    fun `HomeViewModel pull-to-refresh separates isRefreshing, guards duplicates, preserves cache on error and offline`() =
        runTest(testDispatcher) {
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val addressRepo = mockk<AddressRepository>(relaxed = true)
            val orderRepo = mockk<OrderRepository>(relaxed = true)
            val trackingRepo = mockk<OrderTrackingRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)

            val cachedRestaurant = TestFixtures.restaurant(id = "r_cached", name = "Cached Kitchen")
            every { restaurantRepo.observeCategories() } returns flowOf(emptyList())
            every { restaurantRepo.observeRestaurants() } returns flowOf(listOf(cachedRestaurant))
            every { restaurantRepo.observeFavoriteRestaurants() } returns flowOf(emptyList())
            every { addressRepo.observeSelectedAddress() } returns flowOf(null)
            every { orderRepo.observeOrders() } returns flowOf(emptyList())
            every { trackingRepo.activeTrackedOrder } returns MutableStateFlow(null)

            val refreshGate = CompletableDeferred<AppResult<Long>>()
            var catalogCalls = 0
            coEvery { restaurantRepo.refreshHomeCatalog() } coAnswers {
                catalogCalls++
                if (catalogCalls == 1) AppResult.Success(1L) else refreshGate.await()
            }
            coEvery { orderRepo.refreshOrders() } returns AppResult.Success(Unit)

            val vm = HomeViewModel(
                observeCategories = ObserveCategoriesUseCase(restaurantRepo),
                observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
                observeFavoriteRestaurants = ObserveFavoriteRestaurantsUseCase(restaurantRepo),
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
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.isRefreshing)
            assertEquals(1, vm.state.value.restaurants.size)

            // Trigger pull-to-refresh twice rapidly -> second is ignored while in-flight
            vm.onIntent(HomeIntent.Refresh)
            runCurrent()
            assertTrue(vm.state.value.isRefreshing)
            assertFalse(vm.state.value.isLoading)

            vm.onIntent(HomeIntent.Refresh)
            runCurrent()
            assertEquals(2, catalogCalls)

            // Fail refresh -> existing cached restaurants remain intact and error snackbar is emitted
            vm.effects.test {
                refreshGate.complete(AppResult.Error(AppError.Timeout("Network timeout")))
                advanceUntilIdle()

                val effect = awaitItem()
                assertTrue(effect is HomeEffect.ShowMessage)
                assertFalse(vm.state.value.isRefreshing)
                assertEquals(listOf(cachedRestaurant), vm.state.value.restaurants)

                // Offline refresh -> does not hit remote again, emits offline message, preserves data
                connectivity.setSimulatedOffline(true)
                runCurrent()
                vm.onIntent(HomeIntent.Refresh)
                advanceUntilIdle()

                val offlineEffect = awaitItem()
                assertTrue(offlineEffect is HomeEffect.ShowMessage)
                assertEquals(2, catalogCalls)
                assertEquals(listOf(cachedRestaurant), vm.state.value.restaurants)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `RestaurantListViewModel refresh reloads page 0 with active filters and discards stale pagination race`() =
        runTest(testDispatcher) {
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)

            val r1 = TestFixtures.restaurant(id = "r1", name = "Green Leaf", isPureVeg = true)
            val r2 = TestFixtures.restaurant(id = "r2", name = "Old Page 2", isPureVeg = true)
            val rFresh = TestFixtures.restaurant(id = "r_fresh", name = "Fresh Leaf", isPureVeg = true)

            val restaurantsFlow = MutableStateFlow(listOf(r1, r2, rFresh))
            every { restaurantRepo.observeRestaurants() } returns restaurantsFlow

            val page1Gate = CompletableDeferred<AppResult<PagedResult<com.foodflow.domain.model.Restaurant>>>()
            var page0Calls = 0
            coEvery { restaurantRepo.getRestaurantsPage(any(), any()) } coAnswers {
                val request = firstArg<com.foodflow.domain.model.PageRequest>()
                if (request.pageIndex == 0) {
                    page0Calls++
                    val items = if (page0Calls <= 2) listOf(r1) else listOf(rFresh)
                    AppResult.Success(
                        PagedResult(
                            items = items,
                            pageIndex = 0,
                            pageSize = request.pageSize,
                            totalCount = 2,
                            hasNextPage = true
                        )
                    )
                } else {
                    page1Gate.await()
                }
            }
            coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Success(2L)

            val vm = RestaurantListViewModel(
                observeRestaurants = ObserveRestaurantsUseCase(restaurantRepo),
                getPaginatedRestaurants = GetPaginatedRestaurantsUseCase(restaurantRepo),
                refreshHomeCatalog = RefreshHomeCatalogUseCase(restaurantRepo),
                toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(restaurantRepo),
                connectivityObserver = connectivity
            )
            advanceUntilIdle()

            // Apply pure veg filter and rating sort
            vm.onIntent(RestaurantListIntent.TogglePureVeg)
            vm.onIntent(RestaurantListIntent.SetSort(RestaurantSort.RATING))
            advanceUntilIdle()
            assertTrue(vm.state.value.pureVegOnly)
            assertEquals(RestaurantSort.RATING, vm.state.value.sortBy)

            // Start slow page 1 load, then trigger pull-to-refresh before page 1 completes
            vm.onIntent(RestaurantListIntent.LoadNextPage)
            runCurrent()

            vm.onIntent(RestaurantListIntent.Refresh)
            advanceUntilIdle()

            // Complete stale page 1 response after refresh finished -> must NOT overwrite fresh page 0
            page1Gate.complete(
                AppResult.Success(
                    PagedResult(
                        items = listOf(r2),
                        pageIndex = 1,
                        pageSize = 10,
                        totalCount = 2,
                        hasNextPage = false
                    )
                )
            )
            advanceUntilIdle()

            assertFalse(vm.state.value.isRefreshing)
            assertEquals(listOf("r_fresh"), vm.state.value.restaurants.map { it.id })
            assertTrue(vm.state.value.pureVegOnly)
            assertEquals(RestaurantSort.RATING, vm.state.value.sortBy)

            // Refresh error preserves current items and emits snackbar
            coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Error(AppError.Timeout("Sync failed"))
            vm.effects.test {
                vm.onIntent(RestaurantListIntent.Refresh)
                advanceUntilIdle()
                val effect = awaitItem()
                assertTrue(effect is RestaurantListEffect.ShowMessage)
                assertEquals(listOf("r_fresh"), vm.state.value.restaurants.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `RestaurantDetailViewModel refresh syncs menu and reviews preserving filters and cached items on error`() =
        runTest(testDispatcher) {
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val cartRepo = mockk<CartRepository>(relaxed = true)
            val reviewRepo = mockk<ReviewRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)

            val rest = TestFixtures.restaurant(id = "rest_9")
            val item1 = TestFixtures.foodItem(id = "m1", restaurantId = "rest_9", name = "Paneer Tikka", isVeg = true)
            every { restaurantRepo.observeRestaurant("rest_9") } returns flowOf(rest)
            every { restaurantRepo.observeMenu("rest_9") } returns flowOf(listOf(item1))
            every { cartRepo.observeCartItems() } returns flowOf(emptyList())
            every { reviewRepo.observeRestaurantReviews("rest_9") } returns flowOf(emptyList())
            coEvery { restaurantRepo.refreshRestaurantMenu("rest_9") } returns AppResult.Success(Unit)
            coEvery { reviewRepo.refreshRestaurantReviews("rest_9") } returns AppResult.Success(
                RestaurantRatingSummary("rest_9", 4.6, 120)
            )
            coEvery { restaurantRepo.getRestaurantMenuPage("rest_9", any(), any()) } returns AppResult.Success(
                PagedResult(
                    items = listOf(item1),
                    pageIndex = 0,
                    pageSize = 10,
                    totalCount = 1,
                    hasNextPage = false
                )
            )

            val vm = RestaurantDetailViewModel(
                observeRestaurant = ObserveRestaurantUseCase(restaurantRepo),
                observeMenu = ObserveMenuUseCase(restaurantRepo),
                getPaginatedRestaurantMenu = GetPaginatedRestaurantMenuUseCase(restaurantRepo),
                observeCartItems = ObserveCartItemsUseCase(cartRepo),
                getRestaurantReviews = GetRestaurantReviewsUseCase(reviewRepo),
                refreshRestaurantMenu = RefreshRestaurantMenuUseCase(restaurantRepo),
                refreshRestaurantReviews = RefreshRestaurantReviewsUseCase(reviewRepo),
                toggleFavoriteRestaurant = ToggleFavoriteRestaurantUseCase(restaurantRepo),
                addToCart = AddToCartUseCase(cartRepo),
                updateCartQuantity = UpdateCartQuantityUseCase(cartRepo),
                connectivityObserver = connectivity
            )

            vm.onIntent(RestaurantDetailIntent.Load("rest_9"))
            vm.onIntent(RestaurantDetailIntent.ToggleVegOnly)
            vm.onIntent(RestaurantDetailIntent.SearchChanged("Paneer"))
            advanceUntilIdle()

            // Successful pull-to-refresh
            vm.onIntent(RestaurantDetailIntent.Refresh)
            advanceUntilIdle()
            assertFalse(vm.state.value.isRefreshing)
            assertTrue(vm.state.value.vegOnly)
            assertEquals("Paneer", vm.state.value.searchQuery)
            assertEquals(listOf("m1"), vm.state.value.paginatedMenuItems.map { it.id })
            coVerify(atLeast = 2) { restaurantRepo.refreshRestaurantMenu("rest_9") }
            coVerify(atLeast = 2) { reviewRepo.refreshRestaurantReviews("rest_9") }

            // Error preserves existing menu items and emits error snackbar
            coEvery { restaurantRepo.refreshRestaurantMenu("rest_9") } returns AppResult.Error(AppError.Timeout("Menu sync failed"))
            vm.effects.test {
                vm.onIntent(RestaurantDetailIntent.Refresh)
                advanceUntilIdle()
                val effect = awaitItem()
                assertTrue(effect is RestaurantDetailEffect.ShowMessage)
                assertFalse(vm.state.value.isRefreshing)
                assertEquals(listOf("m1"), vm.state.value.paginatedMenuItems.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FavoritesViewModel pull-to-refresh syncs catalog and preserves favourites on error and offline`() =
        runTest(testDispatcher) {
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)
            val fav = TestFixtures.restaurant(id = "fav_1", name = "Fav Bistro").copy(isFavorite = true)

            every { restaurantRepo.observeFavoriteRestaurants() } returns flowOf(listOf(fav))
            coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Success(1L)

            val vm = FavoritesViewModel(
                observeFavoriteRestaurantsUseCase = ObserveFavoriteRestaurantsUseCase(restaurantRepo),
                refreshHomeCatalog = RefreshHomeCatalogUseCase(restaurantRepo),
                toggleFavoriteRestaurantUseCase = ToggleFavoriteRestaurantUseCase(restaurantRepo),
                connectivityObserver = connectivity
            )
            advanceUntilIdle()
            assertEquals(1, vm.state.value.favorites.size)

            vm.onIntent(FavoritesIntent.Refresh)
            advanceUntilIdle()
            assertFalse(vm.state.value.isRefreshing)
            coVerify(exactly = 1) { restaurantRepo.refreshHomeCatalog() }

            // Failure preserves favorites and emits message
            coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Error(AppError.Timeout("Failed"))
            vm.effects.test {
                vm.onIntent(FavoritesIntent.Refresh)
                advanceUntilIdle()
                assertTrue(awaitItem() is FavoritesEffect.ShowMessage)
                assertFalse(vm.state.value.isRefreshing)
                assertEquals(listOf(fav), vm.state.value.favorites)

                // Offline does not call remote
                connectivity.setSimulatedOffline(true)
                runCurrent()
                vm.onIntent(FavoritesIntent.Refresh)
                advanceUntilIdle()
                assertTrue(awaitItem() is FavoritesEffect.ShowMessage)
                coVerify(exactly = 2) { restaurantRepo.refreshHomeCatalog() }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `OrdersViewModel pull-to-refresh reconciles schedules, refreshes orders, and preserves cached orders on error`() =
        runTest(testDispatcher) {
            val orderRepo = mockk<OrderRepository>(relaxed = true)
            val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
            val prefsRepo = mockk<UserPreferencesRepository>(relaxed = true)
            val pdfGenerator = mockk<InvoicePdfGenerator>(relaxed = true)
            val reviewRepo = mockk<ReviewRepository>(relaxed = true)
            val scheduledRepo = mockk<ScheduledOrderRepository>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)

            val existingOrder = sampleOrder("FF-ORD-1", OrderStatus.PREPARING)
            every { orderRepo.observeOrders() } returns flowOf(listOf(existingOrder))
            every { restaurantRepo.observeRestaurants() } returns flowOf(emptyList())
            every { prefsRepo.preferences } returns flowOf(UserPreferences())
            every { scheduledRepo.observeScheduledOrders() } returns flowOf(emptyList())
            every { scheduledRepo.observeUnacknowledgedFailedSchedules() } returns flowOf(emptyList())
            coEvery { scheduledRepo.reconcileOverdueSchedules() } returns 0
            coEvery { orderRepo.refreshOrders() } returns AppResult.Success(Unit)

            val vm = OrdersViewModel(
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
                formatScheduledPlacement = FormatScheduledPlacementUseCase(),
                connectivityObserver = connectivity
            )
            advanceUntilIdle()

            vm.onIntent(OrdersIntent.Refresh)
            advanceUntilIdle()
            assertFalse(vm.state.value.isRefreshing)
            coVerify(atLeast = 2) { orderRepo.refreshOrders() }
            coVerify(atLeast = 2) { scheduledRepo.reconcileOverdueSchedules() }

            // Error preserves orders and emits message
            coEvery { orderRepo.refreshOrders() } returns AppResult.Error(AppError.Timeout("Order sync error"))
            vm.effects.test {
                vm.onIntent(OrdersIntent.Refresh)
                advanceUntilIdle()
                assertTrue(awaitItem() is OrdersEffect.ShowMessage)
                assertEquals(1, vm.state.value.orders.size)

                // Offline emits offline message without additional remote calls
                connectivity.setSimulatedOffline(true)
                runCurrent()
                vm.onIntent(OrdersIntent.Refresh)
                advanceUntilIdle()
                assertTrue(awaitItem() is OrdersEffect.ShowMessage)
                assertEquals(1, vm.state.value.orders.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `OrderTrackingViewModel pull-to-refresh guards duplicates and handles offline gracefully`() =
        runTest(testDispatcher) {
            val orderRepo = mockk<OrderRepository>(relaxed = true)
            val trackingRepo = mockk<OrderTrackingRepository>(relaxed = true)
            val routeRepo = mockk<DeliveryRouteRepository>(relaxed = true)
            val locationProvider = mockk<DeliveryLocationProvider>(relaxed = true)
            val connectivity = FakeConnectivityObserver(true)

            val activeOrder = sampleOrder("FF-TRK-1", OrderStatus.PICKED_UP)
            every { orderRepo.observeOrder("FF-TRK-1") } returns flowOf(activeOrder)
            every { orderRepo.observeOrderTimeline("FF-TRK-1") } returns flowOf(emptyList())
            every { locationProvider.observeDeliveryLocation("FF-TRK-1") } returns flowOf(null)
            every { trackingRepo.connectionState } returns MutableStateFlow(WebSocketConnectionState.CONNECTED)
            every { trackingRepo.diagnostics } returns MutableStateFlow(TrackingDiagnosticInfo())
            every { trackingRepo.activeTrackedOrder } returns MutableStateFlow(activeOrder)

            val reconcileGate = CompletableDeferred<AppResult<Order>>()
            coEvery { trackingRepo.reconcileOrder("FF-TRK-1") } coAnswers { reconcileGate.await() }

            val vm = OrderTrackingViewModel(
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
            vm.onIntent(OrderTrackingIntent.Start("FF-TRK-1"))
            advanceUntilIdle()

            // Double pull while reconciling -> only 1 reconcile invocation
            vm.onIntent(OrderTrackingIntent.ReconnectAndReconcile)
            runCurrent()
            assertTrue(vm.state.value.isReconciling)
            vm.onIntent(OrderTrackingIntent.ReconnectAndReconcile)
            runCurrent()
            coVerify(exactly = 1) { trackingRepo.reconcileOrder("FF-TRK-1") }

            vm.effects.test {
                reconcileGate.complete(AppResult.Success(activeOrder))
                advanceUntilIdle()
                assertTrue(awaitItem() is OrderTrackingEffect.ShowMessage)
                assertFalse(vm.state.value.isReconciling)

                // Offline -> skips reconcile and notifies user
                connectivity.setSimulatedOffline(true)
                runCurrent()
                vm.onIntent(OrderTrackingIntent.ReconnectAndReconcile)
                advanceUntilIdle()
                assertTrue(awaitItem() is OrderTrackingEffect.ShowMessage)
                coVerify(exactly = 1) { trackingRepo.reconcileOrder("FF-TRK-1") }
                cancelAndIgnoreRemainingEvents()
            }
        }

    // -------------------------------------------------------------------------
    // Collapsing Bottom Navigation State & Animation UI Tests
    // -------------------------------------------------------------------------

    @Test
    fun `BottomNavScrollState ignores micro-jitter, horizontal swipes, non-scrollable screens, and keyboard scroll`() {
        val state = BottomNavScrollState(scrollHysteresisPx = 24f, minScrollDeltaPx = 2f)
        state.setActiveTab(0)
        val connection = state.createNestedScrollConnection { state.activeTabIndex }

        // Default at top is expanded
        assertFalse(state.isCollapsed(0))
        assertTrue(state.shouldShowLabels(0))

        // Non-scrollable screen (consumed.y == 0f, available.y < 0f) stays expanded
        connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, -120f),
            source = NestedScrollSource.UserInput
        )
        assertFalse(state.isCollapsed(0))

        // Horizontal swipe (absX > absY) is ignored even when away from top
        state.onTabAtTopChanged(0, false)
        connection.onPostScroll(
            consumed = Offset(-80f, -30f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput
        )
        assertFalse(state.isCollapsed(0))

        // Tiny micro-jitter below minScrollDeltaPx is ignored
        repeat(20) {
            connection.onPostScroll(
                consumed = Offset(0f, -1f),
                available = Offset.Zero,
                source = NestedScrollSource.UserInput
            )
        }
        assertFalse(state.isCollapsed(0))

        // Downward consumed scroll past threshold collapses
        connection.onPostScroll(
            consumed = Offset(0f, -30f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput
        )
        assertTrue(state.isCollapsed(0))
        assertFalse(state.shouldShowLabels(0))

        // Switching to Tab 1 expands cleanly without carrying Tab 0's collapsed state
        state.setActiveTab(1)
        assertFalse(state.isCollapsed(1))
        assertTrue(state.shouldShowLabels(1))

        // Keyboard open suppresses focus/BringIntoView scroll deltas
        state.onTabAtTopChanged(1, false)
        state.onKeyboardVisibilityChanged(true)
        connection.onPostScroll(
            consumed = Offset(0f, -90f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput
        )
        assertFalse(state.isCollapsed(1))
        state.onKeyboardVisibilityChanged(false)

        // Collapse Tab 1 then pull down at top boundary -> guarantees expanded
        connection.onPostScroll(
            consumed = Offset(0f, -40f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput
        )
        assertTrue(state.isCollapsed(1))
        connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, 40f),
            source = NestedScrollSource.UserInput
        )
        assertFalse(state.isCollapsed(1))
        assertTrue(state.shouldShowLabels(1))
    }

    @Test
    fun `FloatingCapsuleBottomNavBar reduces height when collapsed, keeps 48dp touch targets and unclipped Cart badge`() {
        var activeTab by mutableIntStateOf(0)
        var showLabels by mutableStateOf(true)

        composeRule.setContent {
            FoodFlowTheme {
                FloatingCapsuleBottomNavBar(
                    destinations = FoodFlowTopLevelDestinations,
                    activeTabIndex = activeTab,
                    cartCount = 5,
                    showLabels = showLabels,
                    onSelectTab = { activeTab = it }
                )
            }
        }
        composeRule.waitForIdle()

        val expandedBounds = composeRule.onNodeWithTag("floating_capsule_bottom_nav").getUnclippedBoundsInRoot()
        val expandedHeight = expandedBounds.bottom - expandedBounds.top
        composeRule.onNodeWithTag("nav_home_label", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("cart_badge_count", useUnmergedTree = true).assertIsDisplayed()

        // Collapse to icon-only compact capsule
        showLabels = false
        composeRule.waitForIdle()

        val collapsedBounds = composeRule.onNodeWithTag("floating_capsule_bottom_nav").getUnclippedBoundsInRoot()
        val collapsedHeight = collapsedBounds.bottom - collapsedBounds.top
        assertTrue(
            "Expected collapsed nav height ($collapsedHeight) < expanded height ($expandedHeight)",
            collapsedHeight < expandedHeight
        )
        composeRule.onNodeWithTag("nav_home_label", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("cart_badge_count", useUnmergedTree = true).assertIsDisplayed()

        // Verify >= 48dp accessible touch targets remain intact in collapsed mode and clicking works
        composeRule.onNodeWithTag("nav_orders")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(3, activeTab)
    }

    @Test
    fun `HomeScreenContent renders pull-to-refresh container alongside home feed`() {
        composeRule.setContent {
            FoodFlowTheme {
                HomeScreenContent(
                    state = HomeUiState(
                        isLoading = false,
                        isRefreshing = false,
                        restaurants = listOf(TestFixtures.restaurant(name = "Spice Route Kitchen"))
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onIntent = {},
                    onOpenLocation = {},
                    onOpenSearch = {},
                    onOpenRestaurantList = {},
                    onOpenRestaurantDetail = {},
                    onOpenTracking = {},
                    onOpenFavorites = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_pull_to_refresh").assertIsDisplayed()
        composeRule.onNodeWithTag("home_feed_list")
            .assertIsDisplayed()
            .performScrollToNode(hasText("All Restaurants (1)"))
        composeRule.onNodeWithText("All Restaurants (1)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Spice Route Kitchen").onFirst().assertIsDisplayed()
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
