package com.foodflow.feature.home

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.order.RefreshOrdersUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCategoriesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveFavoriteRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.tracking.DismissDeliveredOrderBannerUseCase
import com.foodflow.domain.usecase.tracking.EnsureActiveOrderTrackingUseCase
import com.foodflow.domain.usecase.tracking.ObserveActiveTrackedOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeCategories: ObserveCategoriesUseCase,
    observeRestaurants: ObserveRestaurantsUseCase,
    observeFavoriteRestaurants: ObserveFavoriteRestaurantsUseCase,
    observeSelectedAddress: ObserveSelectedAddressUseCase,
    observeOrders: ObserveOrdersUseCase,
    observeActiveTrackedOrder: ObserveActiveTrackedOrderUseCase,
    private val ensureActiveOrderTracking: EnsureActiveOrderTrackingUseCase,
    private val dismissDeliveredOrderBanner: DismissDeliveredOrderBannerUseCase,
    private val refreshHomeCatalog: RefreshHomeCatalogUseCase,
    private val refreshOrders: RefreshOrdersUseCase,
    private val toggleFavoriteRestaurant: ToggleFavoriteRestaurantUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<HomeUiState, HomeIntent, HomeEffect>(HomeUiState()) {

    private var refreshJob: Job? = null

    init {
        // Ensure application-scoped real-time tracking is active even if user landed
        // directly on Dashboard without ever opening the Track Order screen.
        ensureActiveOrderTracking()

        val catalogAndLocationFlow = combine(
            observeCategories(),
            observeRestaurants(),
            observeFavoriteRestaurants(),
            observeSelectedAddress()
        ) { categories, restaurants, favorites, address ->
            QuadrupleHomeFeed(categories, restaurants, favorites.size, address)
        }

        combine(
            catalogAndLocationFlow,
            observeOrders(),
            observeActiveTrackedOrder(),
            connectivityObserver.isOnline
        ) { feed, orders, trackedOrder, isOnline ->
            val activeOrder = trackedOrder?.takeIf { it.orderStatus.isActive }
                ?: orders.firstOrNull { it.orderStatus.isActive }
            val deliveredBanner = if (activeOrder == null) {
                trackedOrder?.takeIf { it.orderStatus == OrderStatus.DELIVERED }
            } else {
                null
            }
            updateState {
                copy(
                    isLoading = false,
                    isOffline = !isOnline,
                    selectedAddress = feed.address,
                    categories = feed.categories,
                    restaurants = feed.restaurants,
                    favoriteCount = feed.favoriteCount,
                    activeOrder = activeOrder,
                    deliveredOrderBanner = deliveredBanner
                )
            }
        }.launchIn(viewModelScope)

        // Automatic background catalog sync on app/home open
        viewModelScope.launch {
            if (connectivityObserver.isCurrentlyOnline()) {
                refreshHomeCatalog()
            }
        }
    }

    /** Handles dashboard actions such as filters, refresh, and favorite toggles. */
    override fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.Refresh -> handleRefresh()
            is HomeIntent.SelectCategory -> updateState {
                copy(selectedCategory = if (selectedCategory == intent.categoryName) null else intent.categoryName)
            }
            HomeIntent.TogglePureVeg -> updateState { copy(pureVegOnly = !pureVegOnly) }
            HomeIntent.ToggleFastDelivery -> updateState { copy(fastDeliveryOnly = !fastDeliveryOnly) }
            HomeIntent.ToggleTopRated -> updateState { copy(topRatedOnly = !topRatedOnly) }
            is HomeIntent.ToggleFavorite -> toggleFavorite(intent.restaurantId)
            HomeIntent.DismissDeliveredBanner -> dismissDeliveredOrderBanner()
            HomeIntent.DismissError -> updateState { copy(errorMessage = null) }
        }
    }

    /** Refreshes the home catalog and orders, reporting failures to the screen. */
    private fun handleRefresh() {
        if (state.value.isRefreshing || refreshJob?.isActive == true) return

        if (!connectivityObserver.isCurrentlyOnline()) {
            emitEffect(HomeEffect.ShowMessage("You're offline. Showing saved restaurants."))
            return
        }

        refreshJob = viewModelScope.launch {
            updateState { copy(isRefreshing = true, errorMessage = null) }
            try {
                ensureActiveOrderTracking()
                val (catalogResult, ordersResult) = coroutineScope {
                    val catalogDeferred = async { refreshHomeCatalog() }
                    val ordersDeferred = async { refreshOrders() }
                    catalogDeferred.await() to ordersDeferred.await()
                }
                val firstError = (catalogResult as? AppResult.Error)?.error
                    ?: (ordersResult as? AppResult.Error)?.error
                if (firstError != null) {
                    updateState { copy(errorMessage = firstError.userMessage) }
                    emitEffect(HomeEffect.ShowMessage(firstError.userMessage))
                }
            } finally {
                updateState { copy(isRefreshing = false) }
            }
        }
    }

    /** Toggles a restaurant's saved status and shows the result to the user. */
    private fun toggleFavorite(restaurantId: String) {
        viewModelScope.launch {
            when (val res = toggleFavoriteRestaurant(restaurantId)) {
                is AppResult.Success -> {
                    val msg = if (res.data) "Added to favourites" else "Removed from favourites"
                    emitEffect(HomeEffect.ShowMessage(msg))
                }
                is AppResult.Error -> emitEffect(HomeEffect.ShowMessage(res.error.userMessage))
            }
        }
    }
}
