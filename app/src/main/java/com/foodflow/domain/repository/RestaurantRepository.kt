package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantPageFilter
import kotlinx.coroutines.flow.Flow

interface RestaurantRepository {
    /** Streams the categories available for browsing. */
    fun observeCategories(): Flow<List<FoodCategory>>
    /** Streams the saved restaurant catalog. */
    fun observeRestaurants(): Flow<List<Restaurant>>
    /** Streams one restaurant's saved details. */
    fun observeRestaurant(restaurantId: String): Flow<Restaurant?>
    /** Streams menu items saved for one restaurant. */
    fun observeMenu(restaurantId: String): Flow<List<FoodItem>>
    /** Streams available coupons. */
    fun observeCoupons(): Flow<List<Coupon>>
    /** Streams the restaurants the user has saved as favorites. */
    fun observeFavoriteRestaurants(): Flow<List<Restaurant>>
    /** Streams the user's recent search terms. */
    fun observeRecentSearches(): Flow<List<String>>
    /** Searches the locally saved restaurants for a matching query. */
    fun searchRestaurantsLocal(query: String): Flow<List<Restaurant>>
    /** Searches locally saved menu items for a matching query. */
    fun searchFoodItemsLocal(query: String): Flow<List<FoodItem>>
    /** Loads one filtered and sorted page of restaurants. */
    suspend fun getRestaurantsPage(
        request: PageRequest,
        filter: RestaurantPageFilter = RestaurantPageFilter()
    ): AppResult<PagedResult<Restaurant>>
    /** Loads one filtered page of a restaurant's menu. */
    suspend fun getRestaurantMenuPage(
        restaurantId: String,
        request: PageRequest,
        filter: MenuPageFilter = MenuPageFilter()
    ): AppResult<PagedResult<FoodItem>>
    /** Saves a submitted query in recent-search history. */
    suspend fun saveSearchQuery(query: String)
    /** Removes the user's recent-search history. */
    suspend fun clearSearchHistory()
    /** Refreshes the home catalog and returns when it was updated. */
    suspend fun refreshHomeCatalog(): AppResult<Long>
    /** Refreshes the saved menu for one restaurant. */
    suspend fun refreshRestaurantMenu(restaurantId: String): AppResult<Unit>
    /** Toggles a restaurant's favorite status and returns the new value. */
    suspend fun toggleFavorite(restaurantId: String): AppResult<Boolean>
    /** Finds a coupon by code, or returns null when it is not found. */
    suspend fun getCouponByCode(code: String): Coupon?
}
