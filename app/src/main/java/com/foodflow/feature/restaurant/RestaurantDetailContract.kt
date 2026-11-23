package com.foodflow.feature.restaurant

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.PaginationLoadState
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantReview

data class RestaurantDetailUiState(
    val restaurant: Restaurant? = null,
    val menuItems: List<FoodItem> = emptyList(),
    val paginatedMenuItems: List<FoodItem> = emptyList(),
    val menuCurrentPage: Int = 0,
    val menuTotalCount: Int = 0,
    val menuHasNextPage: Boolean = false,
    val menuLoadState: PaginationLoadState = PaginationLoadState.Idle,
    val reviews: List<RestaurantReview> = emptyList(),
    val vegOnly: Boolean = false,
    val searchQuery: String = "",
    val cartQuantities: Map<String, Int> = emptyMap(),
    val cartTotalItems: Int = 0,
    val cartTotalAmount: Double = 0.0,
    val isOffline: Boolean = false,
    val isRefreshing: Boolean = false,
    val customizingItem: FoodItem? = null,
    val pendingMismatchItem: FoodItem? = null,
    val pendingMismatchAddons: List<FoodAddon> = emptyList(),
    val pendingMismatchNotes: String = "",
    val mismatchMessage: String? = null
) : MviState {

    val filteredMenu: List<FoodItem>
        get() = if (paginatedMenuItems.isNotEmpty() || menuLoadState != PaginationLoadState.Idle) {
            paginatedMenuItems
        } else {
            menuItems.filter { item ->
                val vegOk = !vegOnly || item.isVeg
                val queryOk = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.category.contains(searchQuery, ignoreCase = true)
                vegOk && queryOk
            }
        }
}

sealed interface RestaurantDetailIntent : MviIntent {
    data class Load(val restaurantId: String) : RestaurantDetailIntent
    data object ToggleVegOnly : RestaurantDetailIntent
    data class SearchChanged(val query: String) : RestaurantDetailIntent
    data object LoadNextMenuPage : RestaurantDetailIntent
    data object RetryMenuPagination : RestaurantDetailIntent
    data object Refresh : RestaurantDetailIntent
    data object ToggleFavorite : RestaurantDetailIntent
    data class RequestAddItem(val item: FoodItem) : RestaurantDetailIntent
    data class ConfirmAddCustomized(
        val item: FoodItem,
        val addons: List<FoodAddon>,
        val instructions: String
    ) : RestaurantDetailIntent
    data object DismissCustomization : RestaurantDetailIntent
    data object ConfirmClearCartAndAdd : RestaurantDetailIntent
    data object DismissMismatchDialog : RestaurantDetailIntent
    data class IncrementItem(val itemId: String, val currentQty: Int) : RestaurantDetailIntent
    data class DecrementItem(val itemId: String, val currentQty: Int) : RestaurantDetailIntent
}

sealed interface RestaurantDetailEffect : MviEffect {
    data class ShowMessage(val message: String) : RestaurantDetailEffect
}
