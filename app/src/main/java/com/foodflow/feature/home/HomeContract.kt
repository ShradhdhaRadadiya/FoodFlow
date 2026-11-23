package com.foodflow.feature.home

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.PromoBanner
import com.foodflow.domain.model.Restaurant

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val selectedAddress: DeliveryAddress? = null,
    val categories: List<FoodCategory> = emptyList(),
    val promoBanners: List<PromoBanner> = defaultPromoBanners(),
    val selectedCategory: String? = null,
    val pureVegOnly: Boolean = false,
    val fastDeliveryOnly: Boolean = false,
    val topRatedOnly: Boolean = false,
    val restaurants: List<Restaurant> = emptyList(),
    val favoriteCount: Int = 0,
    val activeOrder: Order? = null,
    val deliveredOrderBanner: Order? = null,
    val errorMessage: String? = null
) : MviState {

    val filteredRestaurants: List<Restaurant>
        get() = restaurants.filter { restaurant ->
            val categoryMatch = selectedCategory == null ||
                restaurant.cuisines.any { it.contains(selectedCategory, ignoreCase = true) } ||
                restaurant.name.contains(selectedCategory, ignoreCase = true)
            val vegMatch = !pureVegOnly || restaurant.isPureVeg
            val fastMatch = !fastDeliveryOnly || restaurant.deliveryTimeMin <= 30
            val ratingMatch = !topRatedOnly || restaurant.rating >= 4.4
            categoryMatch && vegMatch && fastMatch && ratingMatch
        }

    val popularRestaurants: List<Restaurant>
        get() = restaurants.sortedByDescending { it.rating }.take(4)

    companion object {
        /** Returns the default promotional banners shown on the dashboard. */
        fun defaultPromoBanners(): List<PromoBanner> = listOf(
            PromoBanner(
                id = "banner_flow50",
                tag = "LIMITED OFFER",
                title = "Flat ₹150 OFF on First Feast",
                subtitle = "Use code FLOW50 on orders above ₹299",
                ctaLabel = "Explore Deals",
                imageKey = "banner_feast",
                accentHex = 0xFF0A7D60,
                categoryFilter = null
            ),
            PromoBanner(
                id = "banner_biryani",
                tag = "CHEF'S SPECIAL",
                title = "Royal Dum Biryani Combos",
                subtitle = "Authentic slow-cooked spices delivered piping hot",
                ctaLabel = "Order Biryani",
                imageKey = "cuisine_indian",
                accentHex = 0xFF0E7490,
                categoryFilter = "Biryani"
            ),
            PromoBanner(
                id = "banner_south",
                tag = "EXPRESS 20 MIN",
                title = "Crispy Dosas & Filter Coffee",
                subtitle = "Zero delivery fee on breakfast & tiffin picks",
                ctaLabel = "Browse South Indian",
                imageKey = "cuisine_south",
                accentHex = 0xFF047857,
                categoryFilter = "South Indian"
            ),
            PromoBanner(
                id = "banner_global",
                tag = "WEEKEND CRAVINGS",
                title = "Wood-Fired Pizzas & Bowls",
                subtitle = "Save up to 20% with code FEAST20",
                ctaLabel = "Discover Now",
                imageKey = "cuisine_global",
                accentHex = 0xFF0369A1,
                categoryFilter = "Pizza"
            )
        )
    }
}

sealed interface HomeIntent : MviIntent {
    data object Refresh : HomeIntent
    data class SelectCategory(val categoryName: String?) : HomeIntent
    data object TogglePureVeg : HomeIntent
    data object ToggleFastDelivery : HomeIntent
    data object ToggleTopRated : HomeIntent
    data class ToggleFavorite(val restaurantId: String) : HomeIntent
    data object DismissDeliveredBanner : HomeIntent
    data object DismissError : HomeIntent
}

sealed interface HomeEffect : MviEffect {
    data class ShowMessage(val message: String) : HomeEffect
}
