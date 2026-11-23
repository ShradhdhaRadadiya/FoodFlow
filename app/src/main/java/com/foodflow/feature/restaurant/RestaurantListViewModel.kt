package com.foodflow.feature.restaurant

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PaginationLoadState
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.usecase.restaurant.GetPaginatedRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestaurantListViewModel @Inject constructor(
    observeRestaurants: ObserveRestaurantsUseCase,
    private val getPaginatedRestaurants: GetPaginatedRestaurantsUseCase,
    private val refreshHomeCatalog: RefreshHomeCatalogUseCase,
    private val toggleFavoriteRestaurant: ToggleFavoriteRestaurantUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<RestaurantListUiState, RestaurantListIntent, RestaurantListEffect>(
    RestaurantListUiState()
) {
    private var pagingJob: Job? = null
    private var refreshJob: Job? = null
    private var pagingGeneration: Long = 0L
    private var hasInitialized = false

    init {
        // Observe live Room updates so favorite toggles and newly submitted ratings reflect
        // immediately on already-loaded paginated cards without resetting scroll position.
        combine(
            observeRestaurants(),
            connectivityObserver.isOnline
        ) { latestRestaurants, online ->
            val byId = latestRestaurants.associateBy { it.id }
            updateState {
                val syncedLoaded = restaurants.mapNotNull { existing -> byId[existing.id] }
                copy(
                    restaurants = syncedLoaded,
                    isOffline = !online
                )
            }
            if (!hasInitialized && latestRestaurants.isNotEmpty()) {
                hasInitialized = true
                loadPage(pageIndex = 0, reset = true)
            }
        }.launchIn(viewModelScope)
    }

    /** Handles restaurant-list filters, pagination, refresh, and favorite actions. */
    override fun onIntent(intent: RestaurantListIntent) {
        when (intent) {
            is RestaurantListIntent.SetCategory -> {
                if (state.value.selectedCategory == intent.category && hasInitialized) return
                hasInitialized = true
                updateState { copy(selectedCategory = intent.category) }
                loadPage(pageIndex = 0, reset = true)
            }
            is RestaurantListIntent.SetSort -> {
                if (state.value.sortBy == intent.sort) return
                updateState { copy(sortBy = intent.sort) }
                loadPage(pageIndex = 0, reset = true)
            }
            RestaurantListIntent.TogglePureVeg -> {
                updateState { copy(pureVegOnly = !pureVegOnly) }
                loadPage(pageIndex = 0, reset = true)
            }
            RestaurantListIntent.ToggleOpenOnly -> {
                updateState { copy(openOnly = !openOnly) }
                loadPage(pageIndex = 0, reset = true)
            }
            RestaurantListIntent.ResetFilters -> {
                updateState {
                    copy(
                        selectedCategory = null,
                        pureVegOnly = false,
                        openOnly = false,
                        sortBy = RestaurantSort.RELEVANCE
                    )
                }
                loadPage(pageIndex = 0, reset = true)
            }
            RestaurantListIntent.Refresh -> handleRefresh()
            RestaurantListIntent.LoadNextPage -> {
                val current = state.value
                if (current.isRefreshing || !current.hasNextPage) return
                if (current.loadState is PaginationLoadState.LoadingInitial ||
                    current.loadState is PaginationLoadState.LoadingMore
                ) {
                    return
                }
                loadPage(pageIndex = current.currentPage + 1, reset = false)
            }
            RestaurantListIntent.RetryPagination -> {
                val current = state.value
                val targetPage = if (current.restaurants.isEmpty()) 0 else current.currentPage + 1
                loadPage(pageIndex = targetPage, reset = targetPage == 0)
            }
            is RestaurantListIntent.ToggleFavorite -> viewModelScope.launch {
                when (val res = toggleFavoriteRestaurant(intent.restaurantId)) {
                    is AppResult.Success -> {
                        val msg = if (res.data) "Added to favourites" else "Removed from favourites"
                        emitEffect(RestaurantListEffect.ShowMessage(msg))
                    }
                    is AppResult.Error -> emitEffect(RestaurantListEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }

    /** Refreshes restaurant data and reloads the first page with the active filters. */
    private fun handleRefresh() {
        if (state.value.isRefreshing || refreshJob?.isActive == true) return

        if (!connectivityObserver.isCurrentlyOnline()) {
            emitEffect(RestaurantListEffect.ShowMessage("You're offline. Showing saved restaurants."))
            return
        }

        // Cancel in-flight pagination and advance generation so stale pages can never overwrite fresh page 0.
        pagingJob?.cancel()
        val generation = ++pagingGeneration

        refreshJob = viewModelScope.launch {
            updateState { copy(isRefreshing = true) }
            try {
                when (val syncResult = refreshHomeCatalog()) {
                    is AppResult.Error -> {
                        emitEffect(RestaurantListEffect.ShowMessage(syncResult.error.userMessage))
                        return@launch
                    }
                    is AppResult.Success -> Unit
                }

                // Re-query page 0 using the active filters without wiping visible items during load
                val current = state.value
                val filter = RestaurantPageFilter(
                    category = current.selectedCategory,
                    sortBy = current.sortBy.domainOption,
                    pureVegOnly = current.pureVegOnly,
                    openOnly = current.openOnly
                )
                when (
                    val pageResult = getPaginatedRestaurants(
                        request = PageRequest(pageIndex = 0, pageSize = PageRequest.DEFAULT_PAGE_SIZE),
                        filter = filter
                    )
                ) {
                    is AppResult.Success -> {
                        if (generation != pagingGeneration) return@launch
                        val page = pageResult.data
                        updateState {
                            copy(
                                restaurants = page.items.distinctBy { it.id },
                                currentPage = page.pageIndex,
                                totalCount = page.totalCount,
                                hasNextPage = page.hasNextPage,
                                loadState = if (page.hasNextPage) {
                                    PaginationLoadState.Idle
                                } else {
                                    PaginationLoadState.EndReached
                                }
                            )
                        }
                    }
                    is AppResult.Error -> {
                        emitEffect(RestaurantListEffect.ShowMessage(pageResult.error.userMessage))
                    }
                }
            } finally {
                updateState { copy(isRefreshing = false) }
            }
        }
    }

    /** Loads a restaurant page and merges or replaces the current list as requested. */
    private fun loadPage(pageIndex: Int, reset: Boolean) {
        if (reset) {
            pagingJob?.cancel()
            pagingGeneration++
        } else if (pagingJob?.isActive == true || state.value.isRefreshing) {
            return
        }

        val generation = pagingGeneration
        val current = state.value
        val filter = RestaurantPageFilter(
            category = current.selectedCategory,
            sortBy = current.sortBy.domainOption,
            pureVegOnly = current.pureVegOnly,
            openOnly = current.openOnly
        )

        pagingJob = viewModelScope.launch {
            updateState {
                copy(
                    loadState = if (reset) PaginationLoadState.LoadingInitial else PaginationLoadState.LoadingMore,
                    restaurants = if (reset) emptyList() else restaurants
                )
            }

            when (
                val result = getPaginatedRestaurants(
                    request = PageRequest(pageIndex = pageIndex, pageSize = PageRequest.DEFAULT_PAGE_SIZE),
                    filter = filter
                )
            ) {
                is AppResult.Success -> {
                    if (generation != pagingGeneration) return@launch
                    val page = result.data
                    updateState {
                        val merged = if (reset) {
                            page.items.distinctBy { it.id }
                        } else {
                            (restaurants + page.items).distinctBy { it.id }
                        }
                        copy(
                            restaurants = merged,
                            currentPage = page.pageIndex,
                            totalCount = page.totalCount,
                            hasNextPage = page.hasNextPage,
                            loadState = if (page.hasNextPage) {
                                PaginationLoadState.Idle
                            } else {
                                PaginationLoadState.EndReached
                            }
                        )
                    }
                }
                is AppResult.Error -> {
                    if (generation != pagingGeneration) return@launch
                    updateState {
                        copy(
                            loadState = PaginationLoadState.Error(
                                message = result.error.userMessage,
                                isInitialLoad = reset
                            )
                        )
                    }
                }
            }
        }
    }
}
