package com.foodflow.feature.search

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.restaurant.ClearSearchHistoryUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRecentSearchesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.SaveSearchQueryUseCase
import com.foodflow.domain.usecase.restaurant.SearchFoodItemsUseCase
import com.foodflow.domain.usecase.restaurant.SearchRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    observeRecentSearches: ObserveRecentSearchesUseCase,
    observeRestaurants: ObserveRestaurantsUseCase,
    private val searchRestaurants: SearchRestaurantsUseCase,
    private val searchFoodItems: SearchFoodItemsUseCase,
    private val saveSearchQuery: SaveSearchQueryUseCase,
    private val clearSearchHistory: ClearSearchHistoryUseCase,
    private val toggleFavoriteRestaurant: ToggleFavoriteRestaurantUseCase,
    connectivityObserver: ConnectivityObserver
) : MviViewModel<SearchUiState, SearchIntent, SearchEffect>(SearchUiState()) {

    private val queryFlow = MutableStateFlow("")

    init {
        combine(
            observeRecentSearches(),
            observeRestaurants(),
            connectivityObserver.isOnline
        ) { recent, allRestaurants, online ->
            updateState {
                copy(
                    recentSearches = recent,
                    allRestaurants = allRestaurants,
                    isOffline = !online
                )
            }
        }.launchIn(viewModelScope)

        queryFlow
            .debounce(260L)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                val trimmed = q.trim()
                if (trimmed.isEmpty()) {
                    flowOf(Pair(emptyList(), emptyList()))
                } else {
                    updateState { copy(isSearching = true, errorMessage = null) }
                    combine(
                        searchRestaurants(trimmed),
                        searchFoodItems(trimmed)
                    ) { restaurants, dishes ->
                        Pair(restaurants, dishes)
                    }
                }
            }
            .onEach { (restaurants, dishes) ->
                updateState {
                    copy(
                        isSearching = false,
                        matchingRestaurants = restaurants,
                        matchingDishes = dishes
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /** Handles search text, filters, history, and favorite actions. */
    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> {
                updateState { copy(query = intent.query, isSearching = intent.query.isNotBlank()) }
                queryFlow.value = intent.query
            }
            is SearchIntent.SubmitSearch -> {
                val trimmed = intent.query.trim()
                updateState { copy(query = trimmed) }
                queryFlow.value = trimmed
                if (trimmed.isNotBlank()) {
                    viewModelScope.launch { saveSearchQuery(trimmed) }
                }
            }
            SearchIntent.ClearQuery -> {
                updateState { copy(query = "", isSearching = false) }
                queryFlow.value = ""
            }
            SearchIntent.ClearHistory -> {
                viewModelScope.launch { clearSearchHistory() }
            }
            SearchIntent.TogglePureVeg -> updateState {
                val next = if (dietaryFilter == SearchDietaryFilter.VEG) {
                    SearchDietaryFilter.ALL
                } else {
                    SearchDietaryFilter.VEG
                }
                copy(dietaryFilter = next)
            }
            is SearchIntent.SelectDietary -> updateState {
                copy(dietaryFilter = intent.filter)
            }
            is SearchIntent.SelectCuisine -> updateState {
                copy(selectedCuisine = if (selectedCuisine == intent.cuisine) null else intent.cuisine)
            }
            is SearchIntent.SelectPriceFilter -> updateState {
                copy(priceFilter = intent.filter)
            }
            SearchIntent.ToggleFastDelivery -> updateState { copy(fastDeliveryOnly = !fastDeliveryOnly) }
            is SearchIntent.SelectSort -> updateState { copy(sortBy = intent.sort) }
            SearchIntent.ResetFilters -> updateState {
                copy(
                    selectedCuisine = null,
                    dietaryFilter = SearchDietaryFilter.ALL,
                    priceFilter = SearchPriceFilter.ALL,
                    fastDeliveryOnly = false,
                    sortBy = SearchSortOption.RELEVANCE
                )
            }
            is SearchIntent.ToggleFavorite -> {
                viewModelScope.launch {
                    when (val res = toggleFavoriteRestaurant(intent.restaurantId)) {
                        is AppResult.Success -> {
                            val msg = if (res.data) "Added to favourites" else "Removed from favourites"
                            emitEffect(SearchEffect.ShowMessage(msg))
                        }
                        is AppResult.Error -> emitEffect(SearchEffect.ShowMessage(res.error.userMessage))
                    }
                }
            }
        }
    }
}
