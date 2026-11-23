package com.foodflow.feature.restaurant

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.PaginationLoadState
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantSortOption

enum class RestaurantSort(val label: String, val domainOption: RestaurantSortOption) {
    RELEVANCE("Relevance", RestaurantSortOption.RELEVANCE),
    RATING("Rating", RestaurantSortOption.RATING),
    DELIVERY_TIME("Fastest", RestaurantSortOption.DELIVERY_TIME),
    COST_LOW("Cost: Low", RestaurantSortOption.COST_LOW)
}

data class RestaurantListUiState(
    val selectedCategory: String? = null,
    val sortBy: RestaurantSort = RestaurantSort.RELEVANCE,
    val pureVegOnly: Boolean = false,
    val openOnly: Boolean = false,
    val isOffline: Boolean = false,
    val isRefreshing: Boolean = false,
    val restaurants: List<Restaurant> = emptyList(),
    val currentPage: Int = 0,
    val totalCount: Int = 0,
    val hasNextPage: Boolean = false,
    val loadState: PaginationLoadState = PaginationLoadState.Idle
) : MviState {
    val visibleRestaurants: List<Restaurant>
        get() = restaurants
}

sealed interface RestaurantListIntent : MviIntent {
    data class SetCategory(val category: String?) : RestaurantListIntent
    data class SetSort(val sort: RestaurantSort) : RestaurantListIntent
    data object TogglePureVeg : RestaurantListIntent
    data object ToggleOpenOnly : RestaurantListIntent
    data class ToggleFavorite(val restaurantId: String) : RestaurantListIntent
    data object LoadNextPage : RestaurantListIntent
    data object RetryPagination : RestaurantListIntent
    data object Refresh : RestaurantListIntent
    data object ResetFilters : RestaurantListIntent
}

sealed interface RestaurantListEffect : MviEffect {
    data class ShowMessage(val message: String) : RestaurantListEffect
}
