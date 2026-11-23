package com.foodflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.CheckoutAttemptEntity
import com.foodflow.data.local.entity.CouponEntity
import com.foodflow.data.local.entity.DeliveryPartnerEntity
import com.foodflow.data.local.entity.FavoriteEntity
import com.foodflow.data.local.entity.FoodCategoryEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.MockServerStateEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.entity.PendingOperationEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.local.entity.SearchHistoryEntity
import com.foodflow.data.local.entity.SupportTicketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY id ASC")
    /** Streams saved food categories. */
    fun observeCategories(): Flow<List<FoodCategoryEntity>>

    @Query("SELECT * FROM restaurants ORDER BY isPopular DESC, rating DESC, id ASC")
    /** Streams the saved restaurant catalog in display order. */
    fun observeRestaurants(): Flow<List<RestaurantEntity>>

    @Query("SELECT * FROM restaurants ORDER BY isPopular DESC, rating DESC, id ASC")
    /** Loads the current restaurant catalog once for paging or filtering. */
    suspend fun getAllRestaurantsSnapshot(): List<RestaurantEntity>

    @Query("SELECT * FROM restaurants WHERE id = :restaurantId")
    /** Streams the saved restaurant with this ID. */
    fun observeRestaurant(restaurantId: String): Flow<RestaurantEntity?>

    @Query("SELECT * FROM restaurants WHERE id = :restaurantId")
    /** Loads one saved restaurant by ID. */
    suspend fun getRestaurant(restaurantId: String): RestaurantEntity?

    @Query("SELECT * FROM food_items WHERE restaurantId = :restaurantId ORDER BY isRecommended DESC, isBestseller DESC, name ASC, id ASC")
    /** Streams the saved menu for one restaurant. */
    fun observeMenu(restaurantId: String): Flow<List<FoodItemEntity>>

    @Query("SELECT * FROM food_items WHERE restaurantId = :restaurantId ORDER BY isRecommended DESC, isBestseller DESC, name ASC, id ASC")
    /** Loads a restaurant's saved menu once. */
    suspend fun getMenuSnapshot(restaurantId: String): List<FoodItemEntity>

    @Query("SELECT * FROM food_items WHERE id = :itemId")
    /** Loads one saved food item by ID. */
    suspend fun getFoodItem(itemId: String): FoodItemEntity?

    @Query("SELECT * FROM restaurants WHERE name LIKE '%' || :query || '%' OR cuisinesCsv LIKE '%' || :query || '%' ORDER BY rating DESC")
    /** Streams restaurants whose name or cuisines match the query. */
    fun searchRestaurants(query: String): Flow<List<RestaurantEntity>>

    @Query("SELECT * FROM food_items WHERE name LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%' ORDER BY isBestseller DESC, rating DESC")
    /** Streams food items whose name, description, or category match the query. */
    fun searchFoodItems(query: String): Flow<List<FoodItemEntity>>

    @Query("SELECT * FROM coupons ORDER BY isActive DESC, minOrderAmount ASC")
    /** Streams saved coupons in active and minimum-order display order. */
    fun observeCoupons(): Flow<List<CouponEntity>>

    @Query("SELECT * FROM coupons WHERE UPPER(code) = UPPER(:code) LIMIT 1")
    /** Finds a saved coupon by its case-insensitive code. */
    suspend fun getCouponByCode(code: String): CouponEntity?

    @Query("SELECT COUNT(*) FROM restaurants")
    /** Returns how many restaurants are currently saved. */
    suspend fun countRestaurants(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves categories, replacing rows with matching IDs. */
    suspend fun upsertCategories(items: List<FoodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves restaurants, replacing rows with matching IDs. */
    suspend fun upsertRestaurants(items: List<RestaurantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves food items, replacing rows with matching IDs. */
    suspend fun upsertFoodItems(items: List<FoodItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves coupons, replacing rows with matching IDs. */
    suspend fun upsertCoupons(items: List<CouponEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves delivery partners, replacing rows with matching IDs. */
    suspend fun upsertDeliveryPartners(items: List<DeliveryPartnerEntity>)

    @Query("SELECT * FROM delivery_partners")
    /** Streams saved delivery partners. */
    fun observeDeliveryPartners(): Flow<List<DeliveryPartnerEntity>>

    @Query("SELECT * FROM delivery_partners WHERE id = :id")
    /** Loads one saved delivery partner by ID. */
    suspend fun getDeliveryPartner(id: String): DeliveryPartnerEntity?

    @Query("DELETE FROM restaurants")
    /** Deletes all saved restaurants. */
    suspend fun clearRestaurants()

    @Query("DELETE FROM food_items")
    /** Deletes all saved menu items. */
    suspend fun clearFoodItems()

    @Query("DELETE FROM categories")
    /** Deletes all saved food categories. */
    suspend fun clearCategories()

    @Query("DELETE FROM coupons")
    /** Deletes all saved coupons. */
    suspend fun clearCoupons()
}

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items WHERE userId = :userId ORDER BY addedAtMs ASC, itemId ASC")
    /** Streams the user's cart ordered by when its items were added. */
    fun observeCartItems(userId: String): Flow<List<CartItemEntity>>

    @Query("SELECT * FROM cart_items WHERE userId = :userId ORDER BY addedAtMs ASC, itemId ASC")
    /** Loads the user's current cart once. */
    suspend fun getCartItems(userId: String): List<CartItemEntity>

    @Query("SELECT * FROM cart_items WHERE itemId = :itemId AND userId = :userId")
    /** Finds one cart item belonging to this user. */
    suspend fun getCartItem(itemId: String, userId: String): CartItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves one cart item, replacing its existing row when present. */
    suspend fun upsertCartItem(item: CartItemEntity)

    @Query("DELETE FROM cart_items WHERE itemId = :itemId AND userId = :userId")
    /** Deletes one item from the specified user's cart. */
    suspend fun deleteCartItem(itemId: String, userId: String)

    @Query("DELETE FROM cart_items WHERE userId = :userId")
    /** Deletes every cart item belonging to this user. */
    suspend fun clearUserCart(userId: String)

    @Query("UPDATE cart_items SET syncStatus = :status WHERE itemId = :itemId AND userId = :userId")
    /** Updates the sync state shown for a cart item. */
    suspend fun updateItemSyncStatus(itemId: String, userId: String, status: String)

    @Query("UPDATE cart_items SET unitPrice = :newPrice WHERE itemId = :itemId AND userId = :userId")
    /** Updates a cart item's saved unit price after reconciliation. */
    suspend fun updateItemPrice(itemId: String, userId: String, newPrice: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves an operation that still needs to sync with the data source. */
    suspend fun insertPendingOperation(operation: PendingOperationEntity)

    /**
     * Atomic outbox write: updates the cart item AND queues its pending sync operation
     * inside a single SQLite transaction so process death can never leave local state
     * without a corresponding outbox entry.
     */
    @Transaction
    suspend fun upsertCartWithOutbox(
        item: CartItemEntity,
        operation: PendingOperationEntity,
        clearOtherRestaurant: Boolean
    ) {
        if (clearOtherRestaurant) {
            clearUserCart(item.userId)
        }
        upsertCartItem(item)
        insertPendingOperation(operation)
    }

    @Transaction
    /** Deletes a cart item and queues its sync operation in one transaction. */
    suspend fun deleteCartWithOutbox(
        itemId: String,
        userId: String,
        operation: PendingOperationEntity
    ) {
        deleteCartItem(itemId, userId)
        insertPendingOperation(operation)
    }

    @Transaction
    /** Clears the cart and queues its sync operation in one transaction. */
    suspend fun clearCartWithOutbox(
        userId: String,
        operation: PendingOperationEntity
    ) {
        clearUserCart(userId)
        insertPendingOperation(operation)
    }
}

@Dao
interface PendingOperationDao {
    @Query("SELECT COUNT(*) FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'SYNCING', 'FAILED')")
    /** Streams the number of cart operations that are not fully synced. */
    fun observePendingCount(userId: String): Flow<Int>

    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC")
    /** Loads this user's queued or failed operations in creation order. */
    suspend fun getPendingOperations(userId: String): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC")
    /** Loads queued or failed operations for all users. */
    suspend fun getAllPendingOperations(): List<PendingOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a queued operation, replacing a row with the same operation ID. */
    suspend fun upsert(operation: PendingOperationEntity)

    @Query("UPDATE pending_operations SET status = :status, retryCount = :retryCount, lastError = :lastError, updatedAtMs = :updatedAtMs WHERE operationId = :operationId")
    /** Updates an operation's sync state, retry count, error, and timestamp. */
    suspend fun updateStatus(
        operationId: String,
        status: String,
        retryCount: Int,
        lastError: String?,
        updatedAtMs: Long
    )

    @Query("DELETE FROM pending_operations WHERE userId = :userId")
    /** Deletes pending operations belonging to the specified user. */
    suspend fun clearForUser(userId: String)
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE userId = :userId ORDER BY createdAtMs DESC")
    /** Streams this user's orders from newest to oldest. */
    fun observeOrders(userId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE id = :orderId")
    /** Streams one saved order as it changes. */
    fun observeOrder(orderId: String): Flow<OrderEntity?>

    @Query("SELECT * FROM orders WHERE id = :orderId")
    /** Loads one saved order by ID. */
    suspend fun getOrder(orderId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    /** Finds an order saved with this retry-safe checkout key. */
    suspend fun getOrderByIdempotencyKey(idempotencyKey: String): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves one order, replacing its existing row when present. */
    suspend fun upsertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves multiple orders, replacing rows with matching IDs. */
    suspend fun upsertOrders(orders: List<OrderEntity>)

    @Query("SELECT * FROM order_timeline_events WHERE orderId = :orderId ORDER BY sequenceNumber ASC")
    /** Streams an order's timeline in event sequence order. */
    fun observeTimeline(orderId: String): Flow<List<OrderTimelineEventEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    /** Adds a timeline event unless its unique key is already saved. */
    suspend fun insertTimelineEvent(event: OrderTimelineEventEntity)

    @Transaction
    /** Saves a tracking update and its timeline event together in one transaction. */
    suspend fun applyTrackingEvent(
        updatedOrder: OrderEntity,
        timelineEvent: OrderTimelineEventEntity
    ) {
        upsertOrder(updatedOrder)
        insertTimelineEvent(timelineEvent)
    }

    @Query("SELECT * FROM checkout_attempts WHERE userId = :userId AND status IN ('IN_PROGRESS', 'AWAITING_PAYMENT', 'PAYMENT_PENDING') ORDER BY updatedAtMs DESC LIMIT 1")
    /** Streams the user's most recently updated unfinished checkout attempt. */
    fun observeActiveCheckoutAttempt(userId: String): Flow<CheckoutAttemptEntity?>

    @Query("SELECT * FROM checkout_attempts WHERE userId = :userId AND status IN ('IN_PROGRESS', 'AWAITING_PAYMENT', 'PAYMENT_PENDING') ORDER BY updatedAtMs DESC LIMIT 1")
    /** Loads the user's most recently updated unfinished checkout attempt once. */
    suspend fun getActiveCheckoutAttempt(userId: String): CheckoutAttemptEntity?

    @Query("SELECT * FROM checkout_attempts WHERE idempotencyKey = :key")
    /** Finds a checkout attempt by its retry-safe key. */
    suspend fun getCheckoutAttempt(key: String): CheckoutAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a checkout attempt, replacing a row with the same key. */
    suspend fun upsertCheckoutAttempt(attempt: CheckoutAttemptEntity)

    @Query("DELETE FROM checkout_attempts WHERE idempotencyKey = :key")
    /** Deletes the checkout attempt with this key. */
    suspend fun deleteCheckoutAttempt(key: String)

    @Query("DELETE FROM checkout_attempts WHERE userId = :userId")
    /** Deletes all checkout attempts belonging to this user. */
    suspend fun clearCheckoutAttemptsForUser(userId: String)

    @Query("DELETE FROM orders WHERE userId = :userId")
    /** Deletes all saved orders belonging to this user. */
    suspend fun clearOrdersForUser(userId: String)

    @Query("DELETE FROM order_timeline_events")
    /** Deletes every saved order timeline event. */
    suspend fun clearAllTimelineEvents()
}

@Dao
interface AddressDao {
    @Query("SELECT * FROM addresses WHERE userId = :userId ORDER BY isDefault DESC, id ASC")
    /** Streams this user's addresses with the default address first. */
    fun observeAddresses(userId: String): Flow<List<AddressEntity>>

    @Query("SELECT * FROM addresses WHERE id = :addressId")
    /** Loads one saved address by ID. */
    suspend fun getAddress(addressId: String): AddressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves one address, replacing its existing row when present. */
    suspend fun upsertAddress(address: AddressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves multiple addresses, replacing rows with matching IDs. */
    suspend fun upsertAddresses(addresses: List<AddressEntity>)

    @Query("DELETE FROM addresses WHERE id = :addressId")
    /** Deletes the saved address with this ID. */
    suspend fun deleteAddress(addressId: String)

    @Query("UPDATE addresses SET isDefault = 0 WHERE userId = :userId")
    /** Removes the default flag from all of this user's addresses. */
    suspend fun clearDefaultForUser(userId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT restaurantId FROM favorites WHERE userId = :userId")
    /** Streams the IDs of restaurants saved by this user. */
    fun observeFavoriteIds(userId: String): Flow<List<String>>

    @Query("SELECT restaurantId FROM favorites WHERE userId = :userId")
    /** Loads the IDs of restaurants saved by this user. */
    suspend fun getFavoriteIds(userId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE userId = :userId AND restaurantId = :restaurantId)")
    /** Returns whether this restaurant is saved as this user's favorite. */
    suspend fun isFavorite(userId: String, restaurantId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a favorite relationship, replacing a duplicate row. */
    suspend fun upsertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE userId = :userId AND restaurantId = :restaurantId")
    /** Removes one restaurant from this user's favorites. */
    suspend fun deleteFavorite(userId: String, restaurantId: String)

    @Query("DELETE FROM favorites WHERE userId = :userId")
    /** Deletes all saved favorites belonging to this user. */
    suspend fun clearForUser(userId: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT query FROM search_history WHERE userId = :userId ORDER BY searchedAtMs DESC LIMIT 10")
    /** Streams this user's ten most recently searched queries. */
    fun observeRecentQueries(userId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a search query or replaces the same saved query. */
    suspend fun upsertQuery(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE userId = :userId")
    /** Deletes recent-search history belonging to this user. */
    suspend fun clearForUser(userId: String)
}

@Dao
interface SupportTicketDao {
    @Query("SELECT * FROM support_tickets WHERE userId = :userId ORDER BY createdAtMs DESC")
    /** Streams support tickets for this user, newest first. */
    fun observeTickets(userId: String): Flow<List<SupportTicketEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a support ticket, replacing a row with the same ID. */
    suspend fun insertTicket(ticket: SupportTicketEntity)

    @Query("DELETE FROM support_tickets WHERE userId = :userId")
    /** Deletes support tickets belonging to this user. */
    suspend fun clearForUser(userId: String)
}

@Dao
interface MockServerStateDao {
    @Query("SELECT * FROM mock_server_state WHERE `key` = :key")
    /** Loads one persisted mock-server value by key. */
    suspend fun getValue(key: String): MockServerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a mock-server value, replacing the current row with the same key. */
    suspend fun putValue(entry: MockServerStateEntity)

    @Query("DELETE FROM mock_server_state")
    /** Removes all saved mock-server state. */
    suspend fun clearAll()
}
