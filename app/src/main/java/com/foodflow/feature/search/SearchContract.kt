package com.foodflow.feature.search

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant

enum class SearchSortOption(val label: String) {
    RELEVANCE("Relevance"),
    RATING("Top Rated"),
    DELIVERY_TIME("Fastest"),
    PRICE_LOW("Price: Low")
}

enum class SearchDietaryFilter(val label: String) {
    ALL("All"),
    VEG("Veg"),
    NON_VEG("Non-Veg")
}

enum class SearchPriceFilter(val label: String) {
    ALL("Any Price"),
    UNDER_350("Under ₹350"),
    MID_350_600("₹350–₹600"),
    PREMIUM_600("₹600+")
}

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val isOffline: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val trendingTags: List<String> = listOf("Biryani", "Paneer", "Dosa", "Pizza", "Momos", "Gulab Jamun"),
    val selectedCuisine: String? = null,
    val dietaryFilter: SearchDietaryFilter = SearchDietaryFilter.ALL,
    val priceFilter: SearchPriceFilter = SearchPriceFilter.ALL,
    val fastDeliveryOnly: Boolean = false,
    val sortBy: SearchSortOption = SearchSortOption.RELEVANCE,
    val matchingRestaurants: List<Restaurant> = emptyList(),
    val matchingDishes: List<FoodItem> = emptyList(),
    val allRestaurants: List<Restaurant> = emptyList(),
    val errorMessage: String? = null
) : MviState {

    val pureVegOnly: Boolean
        get() = dietaryFilter == SearchDietaryFilter.VEG

    val availableCuisines: List<String>
        get() = allRestaurants
            .flatMap { it.cuisines }
            .distinct()
            .sorted()

    val hasActiveFilters: Boolean
        get() = selectedCuisine != null ||
            dietaryFilter != SearchDietaryFilter.ALL ||
            priceFilter != SearchPriceFilter.ALL ||
            fastDeliveryOnly ||
            sortBy != SearchSortOption.RELEVANCE

    val filteredRestaurants: List<Restaurant>
        get() {
            val base = if (query.isBlank()) allRestaurants else matchingRestaurants
            val filtered = base.filter { r ->
                val matchesCuisine = selectedCuisine == null ||
                    r.cuisines.any { it.equals(selectedCuisine, ignoreCase = true) }
                val matchesDietary = when (dietaryFilter) {
                    SearchDietaryFilter.ALL -> true
                    SearchDietaryFilter.VEG -> r.isPureVeg
                    SearchDietaryFilter.NON_VEG -> !r.isPureVeg
                }
                val matchesPrice = when (priceFilter) {
                    SearchPriceFilter.ALL -> true
                    SearchPriceFilter.UNDER_350 -> r.priceForTwo < 350
                    SearchPriceFilter.MID_350_600 -> r.priceForTwo in 350..600
                    SearchPriceFilter.PREMIUM_600 -> r.priceForTwo > 600
                }
                val matchesFast = !fastDeliveryOnly || r.deliveryTimeMin <= 30
                matchesCuisine && matchesDietary && matchesPrice && matchesFast
            }
            return when (sortBy) {
                SearchSortOption.RELEVANCE -> filtered
                SearchSortOption.RATING -> filtered.sortedByDescending { it.rating }
                SearchSortOption.DELIVERY_TIME -> filtered.sortedBy { it.deliveryTimeMin }
                SearchSortOption.PRICE_LOW -> filtered.sortedBy { it.priceForTwo }
            }
        }

    val filteredDishes: List<FoodItem>
        get() {
            val filtered = matchingDishes.filter { dish ->
                val matchesDietary = when (dietaryFilter) {
                    SearchDietaryFilter.ALL -> true
                    SearchDietaryFilter.VEG -> dish.isVeg
                    SearchDietaryFilter.NON_VEG -> !dish.isVeg
                }
                val matchesPrice = when (priceFilter) {
                    SearchPriceFilter.ALL -> true
                    SearchPriceFilter.UNDER_350 -> dish.price < 200.0
                    SearchPriceFilter.MID_350_600 -> dish.price in 200.0..350.0
                    SearchPriceFilter.PREMIUM_600 -> dish.price > 350.0
                }
                matchesDietary && matchesPrice
            }
            return when (sortBy) {
                SearchSortOption.RELEVANCE -> filtered
                SearchSortOption.RATING -> filtered.sortedByDescending { it.rating }
                SearchSortOption.DELIVERY_TIME -> filtered
                SearchSortOption.PRICE_LOW -> filtered.sortedBy { it.price }
            }
        }
}

sealed interface SearchIntent : MviIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data class SubmitSearch(val query: String) : SearchIntent
    data object ClearQuery : SearchIntent
    data object ClearHistory : SearchIntent
    data object TogglePureVeg : SearchIntent
    data class SelectDietary(val filter: SearchDietaryFilter) : SearchIntent
    data class SelectCuisine(val cuisine: String?) : SearchIntent
    data class SelectPriceFilter(val filter: SearchPriceFilter) : SearchIntent
    data object ToggleFastDelivery : SearchIntent
    data class SelectSort(val sort: SearchSortOption) : SearchIntent
    data object ResetFilters : SearchIntent
    data class ToggleFavorite(val restaurantId: String) : SearchIntent
}

sealed interface SearchEffect : MviEffect {
    data class ShowMessage(val message: String) : SearchEffect
}
