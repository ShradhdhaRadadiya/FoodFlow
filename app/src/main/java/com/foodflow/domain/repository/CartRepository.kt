package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import kotlinx.coroutines.flow.Flow

interface CartRepository {
    /** Streams the current cart items as local cart data changes. */
    fun observeCartItems(): Flow<List<CartItem>>
    /** Streams how many cart changes are waiting to sync. */
    fun observePendingOperationCount(): Flow<Int>
    /** Streams the time of the most recent successful cart sync. */
    fun observeLastSyncTimestamp(): Flow<Long>
    /** Adds a food item and its chosen options to the cart. */
    suspend fun addItem(
        restaurant: Restaurant,
        foodItem: FoodItem,
        addons: List<FoodAddon>,
        cookingInstructions: String = "",
        clearExistingDifferentRestaurant: Boolean = false
    ): AppResult<Unit>
    /** Changes the quantity of one cart item. */
    suspend fun updateQuantity(itemId: String, quantity: Int): AppResult<Unit>
    /** Removes one item from the cart. */
    suspend fun removeItem(itemId: String): AppResult<Unit>
    /** Saves cooking instructions for one cart item. */
    suspend fun updateCookingInstructions(itemId: String, instructions: String): AppResult<Unit>
    /** Removes all items from the cart. */
    suspend fun clearCart(): AppResult<Unit>
    /** Sends queued cart changes and returns any server-side changes to review. */
    suspend fun syncPendingOperations(): AppResult<List<CartReconciliationNotice>>
    /** Checks current cart prices and availability against the remote data source. */
    suspend fun validateCartWithServer(): AppResult<List<CartReconciliationNotice>>
    /** Schedules background sync for cart changes that could not be sent yet. */
    fun scheduleDeferredSync()
}
