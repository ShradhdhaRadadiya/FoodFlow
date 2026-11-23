package com.foodflow.feature.restaurant

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PaginationLoadState
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.restaurant.GetPaginatedRestaurantMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantUseCase
import com.foodflow.domain.usecase.restaurant.RefreshRestaurantMenuUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.review.GetRestaurantReviewsUseCase
import com.foodflow.domain.usecase.review.RefreshRestaurantReviewsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestaurantDetailViewModel @Inject constructor(
    private val observeRestaurant: ObserveRestaurantUseCase,
    private val observeMenu: ObserveMenuUseCase,
    private val getPaginatedRestaurantMenu: GetPaginatedRestaurantMenuUseCase,
    private val observeCartItems: ObserveCartItemsUseCase,
    private val getRestaurantReviews: GetRestaurantReviewsUseCase,
    private val refreshRestaurantMenu: RefreshRestaurantMenuUseCase,
    private val refreshRestaurantReviews: RefreshRestaurantReviewsUseCase,
    private val toggleFavoriteRestaurant: ToggleFavoriteRestaurantUseCase,
    private val addToCart: AddToCartUseCase,
    private val updateCartQuantity: UpdateCartQuantityUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<RestaurantDetailUiState, RestaurantDetailIntent, RestaurantDetailEffect>(
    RestaurantDetailUiState()
) {
    private var loadJob: Job? = null
    private var menuPagingJob: Job? = null
    private var refreshJob: Job? = null
    private var menuPagingGeneration: Long = 0L
    private var currentRestaurantId: String? = null

    /** Handles menu, review, favorite, cart, and refresh actions for the restaurant screen. */
    override fun onIntent(intent: RestaurantDetailIntent) {
        when (intent) {
            is RestaurantDetailIntent.Load -> load(intent.restaurantId)
            RestaurantDetailIntent.Refresh -> handleRefresh()
            RestaurantDetailIntent.ToggleVegOnly -> {
                updateState { copy(vegOnly = !vegOnly) }
                loadMenuPage(pageIndex = 0, reset = true)
            }
            is RestaurantDetailIntent.SearchChanged -> {
                updateState { copy(searchQuery = intent.query) }
                loadMenuPage(pageIndex = 0, reset = true)
            }
            RestaurantDetailIntent.LoadNextMenuPage -> {
                val current = state.value
                if (current.isRefreshing || !current.menuHasNextPage) return
                if (current.menuLoadState is PaginationLoadState.LoadingInitial ||
                    current.menuLoadState is PaginationLoadState.LoadingMore
                ) {
                    return
                }
                loadMenuPage(pageIndex = current.menuCurrentPage + 1, reset = false)
            }
            RestaurantDetailIntent.RetryMenuPagination -> {
                val current = state.value
                val nextPage = if (current.paginatedMenuItems.isEmpty()) 0 else current.menuCurrentPage + 1
                loadMenuPage(pageIndex = nextPage, reset = nextPage == 0)
            }
            RestaurantDetailIntent.ToggleFavorite -> toggleFavorite()
            is RestaurantDetailIntent.RequestAddItem -> {
                if (intent.item.addons.isNotEmpty()) {
                    updateState { copy(customizingItem = intent.item) }
                } else {
                    addItemToCart(intent.item, emptyList(), "", false)
                }
            }
            is RestaurantDetailIntent.ConfirmAddCustomized -> {
                updateState { copy(customizingItem = null) }
                addItemToCart(intent.item, intent.addons, intent.instructions, false)
            }
            RestaurantDetailIntent.DismissCustomization -> updateState { copy(customizingItem = null) }
            RestaurantDetailIntent.ConfirmClearCartAndAdd -> {
                val item = state.value.pendingMismatchItem ?: return
                val addons = state.value.pendingMismatchAddons
                val notes = state.value.pendingMismatchNotes
                updateState { copy(pendingMismatchItem = null, mismatchMessage = null) }
                addItemToCart(item, addons, notes, clearDifferentRestaurant = true)
            }
            RestaurantDetailIntent.DismissMismatchDialog -> updateState {
                copy(pendingMismatchItem = null, mismatchMessage = null)
            }
            is RestaurantDetailIntent.IncrementItem -> viewModelScope.launch {
                updateCartQuantity(intent.itemId, intent.currentQty + 1)
            }
            is RestaurantDetailIntent.DecrementItem -> viewModelScope.launch {
                updateCartQuantity(intent.itemId, intent.currentQty - 1)
            }
        }
    }

    /** Starts observing the selected restaurant, menu, reviews, and related saved data. */
    private fun load(restaurantId: String) {
        currentRestaurantId = restaurantId
        loadJob?.cancel()
        menuPagingJob?.cancel()
        refreshJob?.cancel()
        menuPagingGeneration++

        loadJob = combine(
            observeRestaurant(restaurantId),
            observeMenu(restaurantId),
            observeCartItems(),
            getRestaurantReviews(restaurantId),
            connectivityObserver.isOnline
        ) { restaurant, menu, cartItems, reviews, online ->
            val qtyMap = cartItems.associate { it.itemId to it.quantity }
            val totalItems = cartItems.sumOf { it.quantity }
            val totalAmount = cartItems.sumOf { it.lineTotal }
            val latestById = menu.associateBy { it.id }
            updateState {
                val syncedPaged = paginatedMenuItems.mapNotNull { existing -> latestById[existing.id] }
                copy(
                    restaurant = restaurant,
                    menuItems = menu,
                    paginatedMenuItems = syncedPaged,
                    reviews = reviews,
                    cartQuantities = qtyMap,
                    cartTotalItems = totalItems,
                    cartTotalAmount = totalAmount,
                    isOffline = !online
                )
            }
            if (state.value.paginatedMenuItems.isEmpty() &&
                menu.isNotEmpty() &&
                state.value.menuLoadState == PaginationLoadState.Idle &&
                !state.value.isRefreshing
            ) {
                loadMenuPage(pageIndex = 0, reset = true)
            }
        }.launchIn(viewModelScope)

        loadMenuPage(pageIndex = 0, reset = true)

        viewModelScope.launch {
            if (connectivityObserver.isCurrentlyOnline()) {
                refreshRestaurantMenu(restaurantId)
                refreshRestaurantReviews(restaurantId)
            }
        }
    }

    /** Refreshes the restaurant menu and reviews while keeping cached content available. */
    private fun handleRefresh() {
        val restaurantId = currentRestaurantId ?: return
        if (state.value.isRefreshing || refreshJob?.isActive == true) return

        if (!connectivityObserver.isCurrentlyOnline()) {
            emitEffect(RestaurantDetailEffect.ShowMessage("You're offline. Showing saved menu."))
            return
        }

        menuPagingJob?.cancel()
        val generation = ++menuPagingGeneration

        refreshJob = viewModelScope.launch {
            updateState { copy(isRefreshing = true) }
            try {
                val (menuResult, reviewsResult) = coroutineScope {
                    val menuDeferred = async { refreshRestaurantMenu(restaurantId) }
                    val reviewsDeferred = async { refreshRestaurantReviews(restaurantId) }
                    menuDeferred.await() to reviewsDeferred.await()
                }

                val firstError = (menuResult as? AppResult.Error)?.error
                    ?: (reviewsResult as? AppResult.Error)?.error
                if (firstError != null) {
                    emitEffect(RestaurantDetailEffect.ShowMessage(firstError.userMessage))
                    if (menuResult is AppResult.Error) {
                        return@launch
                    }
                }

                // Reload page 0 of menu with active filters while keeping current items visible until replacement arrives
                val current = state.value
                val filter = MenuPageFilter(
                    vegOnly = current.vegOnly,
                    searchQuery = current.searchQuery
                )
                when (
                    val pageRes = getPaginatedRestaurantMenu(
                        restaurantId = restaurantId,
                        request = PageRequest(
                            pageIndex = 0,
                            pageSize = PageRequest.DEFAULT_MENU_PAGE_SIZE
                        ),
                        filter = filter
                    )
                ) {
                    is AppResult.Success -> {
                        if (generation != menuPagingGeneration) return@launch
                        val page = pageRes.data
                        updateState {
                            copy(
                                paginatedMenuItems = page.items.distinctBy { it.id },
                                menuCurrentPage = page.pageIndex,
                                menuTotalCount = page.totalCount,
                                menuHasNextPage = page.hasNextPage,
                                menuLoadState = if (page.hasNextPage) {
                                    PaginationLoadState.Idle
                                } else {
                                    PaginationLoadState.EndReached
                                }
                            )
                        }
                    }
                    is AppResult.Error -> {
                        emitEffect(RestaurantDetailEffect.ShowMessage(pageRes.error.userMessage))
                    }
                }
            } finally {
                updateState { copy(isRefreshing = false) }
            }
        }
    }

    /** Loads the requested menu page and updates the visible pagination state. */
    private fun loadMenuPage(pageIndex: Int, reset: Boolean) {
        val restaurantId = currentRestaurantId ?: return
        if (reset) {
            menuPagingJob?.cancel()
            menuPagingGeneration++
        } else if (menuPagingJob?.isActive == true || state.value.isRefreshing) {
            return
        }

        val generation = menuPagingGeneration
        val current = state.value
        val filter = MenuPageFilter(
            vegOnly = current.vegOnly,
            searchQuery = current.searchQuery
        )

        menuPagingJob = viewModelScope.launch {
            updateState {
                copy(
                    menuLoadState = if (reset) PaginationLoadState.LoadingInitial else PaginationLoadState.LoadingMore,
                    paginatedMenuItems = if (reset) emptyList() else paginatedMenuItems
                )
            }
            when (
                val res = getPaginatedRestaurantMenu(
                    restaurantId = restaurantId,
                    request = PageRequest(
                        pageIndex = pageIndex,
                        pageSize = PageRequest.DEFAULT_MENU_PAGE_SIZE
                    ),
                    filter = filter
                )
            ) {
                is AppResult.Success -> {
                    if (generation != menuPagingGeneration) return@launch
                    val page = res.data
                    updateState {
                        val merged = if (reset) {
                            page.items.distinctBy { it.id }
                        } else {
                            (paginatedMenuItems + page.items).distinctBy { it.id }
                        }
                        copy(
                            paginatedMenuItems = merged,
                            menuCurrentPage = page.pageIndex,
                            menuTotalCount = page.totalCount,
                            menuHasNextPage = page.hasNextPage,
                            menuLoadState = if (page.hasNextPage) {
                                PaginationLoadState.Idle
                            } else {
                                PaginationLoadState.EndReached
                            }
                        )
                    }
                }
                is AppResult.Error -> {
                    if (generation != menuPagingGeneration) return@launch
                    updateState {
                        copy(
                            menuLoadState = PaginationLoadState.Error(
                                message = res.error.userMessage,
                                isInitialLoad = reset
                            )
                        )
                    }
                }
            }
        }
    }

    /** Adds or removes this restaurant from the user's favorites. */
    private fun toggleFavorite() {
        val rest = state.value.restaurant ?: return
        viewModelScope.launch {
            when (val res = toggleFavoriteRestaurant(rest.id)) {
                is AppResult.Success -> {
                    val msg = if (res.data) "Added to favourites" else "Removed from favourites"
                    emitEffect(RestaurantDetailEffect.ShowMessage(msg))
                }
                is AppResult.Error -> emitEffect(RestaurantDetailEffect.ShowMessage(res.error.userMessage))
            }
        }
    }

    /** Adds the selected food and options to the cart, reporting any error to the screen. */
    private fun addItemToCart(
        item: FoodItem,
        addons: List<FoodAddon>,
        instructions: String,
        clearDifferentRestaurant: Boolean
    ) {
        val restaurant = state.value.restaurant ?: return
        viewModelScope.launch {
            when (
                val res = addToCart(
                    restaurant = restaurant,
                    foodItem = item,
                    addons = addons,
                    cookingInstructions = instructions,
                    clearExistingDifferentRestaurant = clearDifferentRestaurant
                )
            ) {
                is AppResult.Success -> emitEffect(RestaurantDetailEffect.ShowMessage("${item.name} added to cart"))
                is AppResult.Error -> {
                    if (res.error is AppError.Conflict && res.error.technicalDetail == "RESTAURANT_MISMATCH") {
                        updateState {
                            copy(
                                pendingMismatchItem = item,
                                pendingMismatchAddons = addons,
                                pendingMismatchNotes = instructions,
                                mismatchMessage = res.error.userMessage
                            )
                        }
                    } else {
                        emitEffect(RestaurantDetailEffect.ShowMessage(res.error.userMessage))
                    }
                }
            }
        }
    }
}
