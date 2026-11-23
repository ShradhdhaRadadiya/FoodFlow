package com.foodflow.data.repository

import androidx.work.WorkManager
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.FavoriteDao
import com.foodflow.data.local.dao.PendingOperationDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.FavoriteEntity
import com.foodflow.data.local.entity.PendingOperationEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.remote.api.CartRemoteDataSource
import com.foodflow.data.remote.api.UserRemoteDataSource
import com.foodflow.data.remote.dto.CartOperationRequestDto
import com.foodflow.data.sync.CartSyncWorker
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.repository.CartRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-First Cart Repository implementing the Transactional Outbox Pattern.
 *
 * Conflict-Resolution & Sync Rules:
 * 1. Every add/update/remove runs inside a Room `@Transaction` writing BOTH `cart_items`
 *    and `pending_operations` atomically.
 * 2. Restaurant-specific cart invariant: items from a different restaurant trigger a
 *    typed [AppError.Conflict] unless `clearExistingDifferentRestaurant = true`.
 * 3. Every outbox operation carries a stable UUID `operationId` so server-side dedup
 *    prevents double-application if an acknowledgement is lost mid-flight.
 * 4. Remote server is authoritative on price and stock availability: during sync/validation,
 *    local cart prices update automatically and unavailable items are surfaced via
 *    [CartReconciliationNotice] instead of blindly submitting stale prices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CartRepositoryImpl @Inject constructor(
    private val cartDao: CartDao,
    private val pendingOperationDao: PendingOperationDao,
    private val catalogDao: CatalogDao,
    private val favoriteDao: FavoriteDao,
    private val cartRemote: CartRemoteDataSource,
    private val userRemote: UserRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val workManager: WorkManager,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) : CartRepository {

    private val syncMutex = Mutex()
    private val _lastSyncTimestamp = MutableStateFlow(0L)

    /** Streams the signed-in user's ID, falling back to the demo user when needed. */
    private fun currentUserIdFlow(): Flow<String> =
        preferencesDataStore.preferences.map { it.userId ?: DatabaseSeeder.DEFAULT_USER_ID }

    /** Streams this user's Room cart as domain items. */
    override fun observeCartItems(): Flow<List<CartItem>> =
        currentUserIdFlow().flatMapLatest { userId ->
            cartDao.observeCartItems(userId).map { list -> list.map { it.toDomain(json) } }
        }

    /** Streams the number of cart and favorite changes still waiting to sync. */
    override fun observePendingOperationCount(): Flow<Int> =
        currentUserIdFlow().flatMapLatest { userId ->
            pendingOperationDao.observePendingCount(userId)
        }

    /** Streams the timestamp of the last completed cart sync. */
    override fun observeLastSyncTimestamp(): Flow<Long> = _lastSyncTimestamp.asStateFlow()

    /** Validates and saves a cart item together with its pending sync operation. */
    override suspend fun addItem(
        restaurant: Restaurant,
        foodItem: FoodItem,
        addons: List<FoodAddon>,
        cookingInstructions: String,
        clearExistingDifferentRestaurant: Boolean
    ): AppResult<Unit> = withContext(dispatchers.io) {
        if (!restaurant.isOpen) {
            return@withContext AppResult.Error(
                AppError.Conflict("${restaurant.name} is currently closed and not accepting orders.")
            )
        }
        if (!foodItem.isAvailable) {
            return@withContext AppResult.Error(
                AppError.Conflict("${foodItem.name} is currently unavailable.")
            )
        }

        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        val existingItems = cartDao.getCartItems(userId)
        val hasDifferentRestaurant = existingItems.isNotEmpty() &&
            existingItems.first().restaurantId != restaurant.id

        if (hasDifferentRestaurant && !clearExistingDifferentRestaurant) {
            val existingName = existingItems.first().restaurantName
            return@withContext AppResult.Error(
                AppError.Conflict(
                    userMessage = "Your cart has items from $existingName. Clear cart to order from ${restaurant.name}?",
                    technicalDetail = "RESTAURANT_MISMATCH"
                )
            )
        }

        val now = System.currentTimeMillis()
        val existingSameItem = if (hasDifferentRestaurant) null else cartDao.getCartItem(foodItem.id, userId)
        val newQty = (existingSameItem?.quantity ?: 0) + 1
        val mergedAddons = if (addons.isNotEmpty()) addons else existingSameItem?.let {
            runCatching { json.decodeFromString<List<FoodAddon>>(it.selectedAddonsJson) }.getOrDefault(emptyList())
        }.orEmpty()
        val maxExistingAddedAt = if (hasDifferentRestaurant) {
            0L
        } else {
            existingItems.maxOfOrNull { it.addedAtMs } ?: 0L
        }
        val stableAddedAtMs = existingSameItem?.addedAtMs ?: maxOf(now, maxExistingAddedAt + 1L)

        val cartEntity = CartItemEntity(
            itemId = foodItem.id,
            userId = userId,
            restaurantId = restaurant.id,
            restaurantName = restaurant.name,
            foodName = foodItem.name,
            unitPrice = foodItem.price,
            quantity = newQty,
            isVeg = foodItem.isVeg,
            imageKey = foodItem.imageKey,
            selectedAddonsJson = json.encodeToString(mergedAddons),
            cookingInstructions = cookingInstructions.ifBlank { existingSameItem?.cookingInstructions.orEmpty() },
            syncStatus = "PENDING",
            updatedAtMs = now,
            addedAtMs = stableAddedAtMs
        )

        val op = PendingOperationEntity(
            operationId = "op_cart_${UUID.randomUUID().toString().take(12)}",
            userId = userId,
            entityType = "CART",
            operationType = "UPSERT",
            itemId = foodItem.id,
            restaurantId = restaurant.id,
            quantity = newQty,
            expectedUnitPrice = foodItem.price,
            payloadJson = cartEntity.cookingInstructions,
            status = "PENDING",
            createdAtMs = now,
            updatedAtMs = now
        )

        cartDao.upsertCartWithOutbox(
            item = cartEntity,
            operation = op,
            clearOtherRestaurant = hasDifferentRestaurant && clearExistingDifferentRestaurant
        )

        triggerImmediateOrDeferredSync()
        AppResult.Success(Unit)
    }

    /** Changes an item's quantity and queues the matching sync operation. */
    override suspend fun updateQuantity(itemId: String, quantity: Int): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (quantity <= 0) return@withContext removeItem(itemId)
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val existing = cartDao.getCartItem(itemId, userId)
                ?: return@withContext AppResult.Error(AppError.Validation("Cart item no longer exists."))

            val now = System.currentTimeMillis()
            val updated = existing.copy(
                quantity = quantity.coerceAtMost(20),
                syncStatus = "PENDING",
                updatedAtMs = now
            )
            val op = PendingOperationEntity(
                operationId = "op_qty_${UUID.randomUUID().toString().take(12)}",
                userId = userId,
                entityType = "CART",
                operationType = "UPSERT",
                itemId = itemId,
                restaurantId = existing.restaurantId,
                quantity = updated.quantity,
                expectedUnitPrice = existing.unitPrice,
                payloadJson = existing.cookingInstructions,
                status = "PENDING",
                createdAtMs = now,
                updatedAtMs = now
            )
            cartDao.upsertCartWithOutbox(updated, op, clearOtherRestaurant = false)
            triggerImmediateOrDeferredSync()
            AppResult.Success(Unit)
        }

    /** Removes an item locally and queues the removal for synchronization. */
    override suspend fun removeItem(itemId: String): AppResult<Unit> = withContext(dispatchers.io) {
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        val existing = cartDao.getCartItem(itemId, userId)
            ?: return@withContext AppResult.Success(Unit)
        val now = System.currentTimeMillis()
        val op = PendingOperationEntity(
            operationId = "op_rem_${UUID.randomUUID().toString().take(12)}",
            userId = userId,
            entityType = "CART",
            operationType = "REMOVE",
            itemId = itemId,
            restaurantId = existing.restaurantId,
            quantity = 0,
            expectedUnitPrice = existing.unitPrice,
            payloadJson = "",
            status = "PENDING",
            createdAtMs = now,
            updatedAtMs = now
        )
        cartDao.deleteCartWithOutbox(itemId, userId, op)
        triggerImmediateOrDeferredSync()
        AppResult.Success(Unit)
    }

    /** Saves an item's cooking notes locally and queues the change for synchronization. */
    override suspend fun updateCookingInstructions(itemId: String, instructions: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val existing = cartDao.getCartItem(itemId, userId)
                ?: return@withContext AppResult.Error(AppError.Validation("Item not found in cart."))
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                cookingInstructions = instructions.trim(),
                syncStatus = "PENDING",
                updatedAtMs = now
            )
            val op = PendingOperationEntity(
                operationId = "op_note_${UUID.randomUUID().toString().take(12)}",
                userId = userId,
                entityType = "CART",
                operationType = "UPSERT",
                itemId = itemId,
                restaurantId = existing.restaurantId,
                quantity = existing.quantity,
                expectedUnitPrice = existing.unitPrice,
                payloadJson = updated.cookingInstructions,
                status = "PENDING",
                createdAtMs = now,
                updatedAtMs = now
            )
            cartDao.upsertCartWithOutbox(updated, op, clearOtherRestaurant = false)
            triggerImmediateOrDeferredSync()
            AppResult.Success(Unit)
        }

    /** Clears the local cart and queues the clear operation for synchronization. */
    override suspend fun clearCart(): AppResult<Unit> = withContext(dispatchers.io) {
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        val now = System.currentTimeMillis()
        val op = PendingOperationEntity(
            operationId = "op_clr_${UUID.randomUUID().toString().take(12)}",
            userId = userId,
            entityType = "CART",
            operationType = "CLEAR",
            itemId = "ALL",
            restaurantId = "ALL",
            quantity = 0,
            expectedUnitPrice = 0.0,
            payloadJson = "",
            status = "PENDING",
            createdAtMs = now,
            updatedAtMs = now
        )
        cartDao.clearCartWithOutbox(userId, op)
        triggerImmediateOrDeferredSync()
        AppResult.Success(Unit)
    }

    /**
     * Processes pending outbox entries sequentially under a mutex to avoid duplicate concurrent replays.
     * Reconciles authoritative backend prices and item availability back into Room.
     */
    override suspend fun syncPendingOperations(): AppResult<List<CartReconciliationNotice>> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isCurrentlyOnline()) {
                scheduleDeferredSync()
                return@withContext AppResult.Error(AppError.Offline())
            }

            syncMutex.withLock {
                val pendingOps = pendingOperationDao.getAllPendingOperations()
                if (pendingOps.isEmpty()) {
                    _lastSyncTimestamp.value = System.currentTimeMillis()
                    return@withContext AppResult.Success(emptyList())
                }

                val notices = mutableListOf<CartReconciliationNotice>()
                var firstRetryableError: AppError? = null

                // Coalesce stale intermediate operations on the same cart itemId to avoid blindly replaying obsolete quantities
                val latestByItemKey = pendingOps.groupBy { "${it.entityType}:${it.userId}:${it.itemId}" }

                for ((_, opsForItem) in latestByItemKey) {
                    val latest = opsForItem.last()
                    val obsolete = opsForItem.dropLast(1)
                    val now = System.currentTimeMillis()

                    // Mark superseded intermediate ops as SYNCED so we never replay stale quantities
                    obsolete.forEach { oldOp ->
                        pendingOperationDao.updateStatus(oldOp.operationId, "SYNCED", oldOp.retryCount, null, now)
                    }

                    pendingOperationDao.updateStatus(latest.operationId, "SYNCING", latest.retryCount, null, now)

                    if (latest.entityType == "FAVORITE") {
                        when (val res = userRemote.syncFavorite(latest.userId, latest.restaurantId, latest.operationType == "ADD_FAV")) {
                            is AppResult.Success -> {
                                pendingOperationDao.updateStatus(latest.operationId, "SYNCED", latest.retryCount, null, now)
                                if (latest.operationType == "ADD_FAV") {
                                    favoriteDao.upsertFavorite(FavoriteEntity(latest.userId, latest.restaurantId, "SYNCED", now))
                                }
                            }
                            is AppResult.Error -> {
                                pendingOperationDao.updateStatus(
                                    latest.operationId,
                                    "FAILED",
                                    latest.retryCount + 1,
                                    res.error.technicalDetail,
                                    now
                                )
                                if (res.error.isRetryable && firstRetryableError == null) {
                                    firstRetryableError = res.error
                                }
                            }
                        }
                        continue
                    }

                    val request = CartOperationRequestDto(
                        operationId = latest.operationId,
                        userId = latest.userId,
                        operationType = latest.operationType,
                        itemId = latest.itemId,
                        restaurantId = latest.restaurantId,
                        quantity = latest.quantity,
                        expectedUnitPrice = latest.expectedUnitPrice,
                        instructions = latest.payloadJson
                    )

                    when (val ackRes = cartRemote.syncOperation(request)) {
                        is AppResult.Success -> {
                            val ack = ackRes.data
                            pendingOperationDao.updateStatus(latest.operationId, "SYNCED", latest.retryCount, null, now)

                            if (latest.operationType == "UPSERT") {
                                val currentLocal = cartDao.getCartItem(latest.itemId, latest.userId)
                                if (!ack.restaurantOpen && currentLocal != null) {
                                    notices += CartReconciliationNotice.RestaurantClosed(currentLocal.restaurantName)
                                }
                                if (!ack.itemAvailable && currentLocal != null) {
                                    notices += CartReconciliationNotice.ItemUnavailable(currentLocal.itemId, currentLocal.foodName)
                                    cartDao.deleteCartItem(currentLocal.itemId, latest.userId)
                                } else if (ack.authoritativePrice != null && currentLocal != null) {
                                    if (ack.authoritativePrice != currentLocal.unitPrice) {
                                        notices += CartReconciliationNotice.PriceChanged(
                                            itemId = currentLocal.itemId,
                                            foodName = currentLocal.foodName,
                                            oldPrice = currentLocal.unitPrice,
                                            newPrice = ack.authoritativePrice
                                        )
                                        cartDao.updateItemPrice(currentLocal.itemId, latest.userId, ack.authoritativePrice)
                                    }
                                    cartDao.updateItemSyncStatus(currentLocal.itemId, latest.userId, "SYNCED")
                                }
                            }
                        }
                        is AppResult.Error -> {
                            pendingOperationDao.updateStatus(
                                latest.operationId,
                                "FAILED",
                                latest.retryCount + 1,
                                ackRes.error.technicalDetail,
                                now
                            )
                            if (ackRes.error.isRetryable && firstRetryableError == null) {
                                firstRetryableError = ackRes.error
                            }
                        }
                    }
                }

                if (firstRetryableError != null) {
                    scheduleDeferredSync()
                    AppResult.Error(firstRetryableError)
                } else {
                    _lastSyncTimestamp.value = System.currentTimeMillis()
                    AppResult.Success(notices)
                }
            }
        }

    /** Checks current cart prices and availability and updates local items when needed. */
    override suspend fun validateCartWithServer(): AppResult<List<CartReconciliationNotice>> =
        withContext(dispatchers.io) {
            try {
                val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
                val localItems = cartDao.getCartItems(userId)
                if (localItems.isEmpty()) return@withContext AppResult.Success(emptyList())

                val restaurantId = localItems.first().restaurantId
                val notices = mutableListOf<CartReconciliationNotice>()

                when (val remoteItemsRes = cartRemote.validateCartItems(restaurantId, localItems.map { it.itemId })) {
                    is AppResult.Success -> {
                        val remoteMap = remoteItemsRes.data.associateBy { it.id }
                        for (local in localItems) {
                            val remote = remoteMap[local.itemId]
                            if (remote == null || !remote.isAvailable) {
                                notices += CartReconciliationNotice.ItemUnavailable(local.itemId, local.foodName)
                                cartDao.deleteCartItem(local.itemId, userId)
                            } else if (remote.price != local.unitPrice) {
                                notices += CartReconciliationNotice.PriceChanged(
                                    itemId = local.itemId,
                                    foodName = local.foodName,
                                    oldPrice = local.unitPrice,
                                    newPrice = remote.price
                                )
                                cartDao.updateItemPrice(local.itemId, userId, remote.price)
                            }
                        }
                        val rest = catalogDao.getRestaurant(restaurantId)
                        if (rest != null && !rest.isOpen) {
                            notices += CartReconciliationNotice.RestaurantClosed(rest.name)
                        }
                        AppResult.Success(notices)
                    }
                    is AppResult.Error -> remoteItemsRes
                }
            } catch (t: Throwable) {
                AppResult.Error(com.foodflow.core.common.ErrorMapper.map(t))
            }
        }

    /** Enqueues a network-constrained background job for pending cart changes. */
    override fun scheduleDeferredSync() {
        CartSyncWorker.enqueue(workManager)
    }

    /** Syncs queued changes immediately when online or schedules background work otherwise. */
    private suspend fun triggerImmediateOrDeferredSync() {
        if (connectivityObserver.isCurrentlyOnline()) {
            syncPendingOperations()
        } else {
            scheduleDeferredSync()
        }
    }
}
