package com.foodflow.data.repository

import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.FavoriteDao
import com.foodflow.data.local.dao.PendingOperationDao
import com.foodflow.data.local.dao.SearchHistoryDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.FavoriteEntity
import com.foodflow.data.local.entity.PendingOperationEntity
import com.foodflow.data.local.entity.SearchHistoryEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.data.local.paging.LocalCatalogPagingDataSource
import com.foodflow.data.remote.api.RestaurantRemoteDataSource
import com.foodflow.data.remote.api.UserRemoteDataSource
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first Catalog & Favourites repository.
 *
 * Room is always the Single Source of Truth:
 * - UI observes Room Flows exclusively.
 * - Remote refreshes upsert into Room and NEVER wipe valid cached rows on network failure.
 * - Favorite toggles persist locally immediately and enqueue outbox sync if offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RestaurantRepositoryImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val favoriteDao: FavoriteDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val pendingOperationDao: PendingOperationDao,
    private val restaurantRemote: RestaurantRemoteDataSource,
    private val userRemote: UserRemoteDataSource,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val cartRepository: CartRepository,
    private val localPagingDataSource: LocalCatalogPagingDataSource,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) : RestaurantRepository {

    /** Streams the signed-in user ID, using the demo ID when no user is signed in. */
    private fun currentUserIdFlow(): Flow<String> =
        preferencesDataStore.preferences.map { it.userId ?: DatabaseSeeder.DEFAULT_USER_ID }

    /** Streams saved categories converted to domain objects. */
    override fun observeCategories(): Flow<List<FoodCategory>> =
        catalogDao.observeCategories().map { list -> list.map { it.toDomain() } }

    /** Streams saved restaurants with each user's favorite status applied. */
    override fun observeRestaurants(): Flow<List<Restaurant>> =
        currentUserIdFlow().flatMapLatest { userId ->
            combine(
                catalogDao.observeRestaurants(),
                favoriteDao.observeFavoriteIds(userId)
            ) { restaurants, favIds ->
                val favSet = favIds.toSet()
                restaurants.map { it.toDomain(isFavorite = it.id in favSet) }
            }
        }

    /** Streams one saved restaurant with the current user's favorite status applied. */
    override fun observeRestaurant(restaurantId: String): Flow<Restaurant?> =
        currentUserIdFlow().flatMapLatest { userId ->
            combine(
                catalogDao.observeRestaurant(restaurantId),
                favoriteDao.observeFavoriteIds(userId)
            ) { restaurant, favIds ->
                restaurant?.toDomain(isFavorite = restaurant.id in favIds)
            }
        }

    /** Streams a restaurant's saved menu converted to domain objects. */
    override fun observeMenu(restaurantId: String): Flow<List<FoodItem>> =
        catalogDao.observeMenu(restaurantId).map { list -> list.map { it.toDomain(json) } }

    /** Streams saved coupons converted to domain objects. */
    override fun observeCoupons(): Flow<List<Coupon>> =
        catalogDao.observeCoupons().map { list -> list.map { it.toDomain() } }

    /** Streams only the saved restaurants marked as favorites by this user. */
    override fun observeFavoriteRestaurants(): Flow<List<Restaurant>> =
        observeRestaurants().map { list -> list.filter { it.isFavorite } }

    /** Streams recent search terms belonging to the current user. */
    override fun observeRecentSearches(): Flow<List<String>> =
        currentUserIdFlow().flatMapLatest { userId ->
            searchHistoryDao.observeRecentQueries(userId)
        }

    /** Searches Room restaurants and includes each matching favorite status. */
    override fun searchRestaurantsLocal(query: String): Flow<List<Restaurant>> =
        currentUserIdFlow().flatMapLatest { userId ->
            combine(
                catalogDao.searchRestaurants(query.trim()),
                favoriteDao.observeFavoriteIds(userId)
            ) { restaurants, favIds ->
                val favSet = favIds.toSet()
                restaurants.map { it.toDomain(isFavorite = it.id in favSet) }
            }
        }

    /** Searches saved menu items and converts matches to domain objects. */
    override fun searchFoodItemsLocal(query: String): Flow<List<FoodItem>> =
        catalogDao.searchFoodItems(query.trim()).map { list -> list.map { it.toDomain(json) } }

    /** Loads a filtered page from the local catalog for the current user. */
    override suspend fun getRestaurantsPage(
        request: PageRequest,
        filter: RestaurantPageFilter
    ): AppResult<PagedResult<Restaurant>> {
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        return localPagingDataSource.loadRestaurantsPage(
            userId = userId,
            request = request,
            filter = filter
        )
    }

    /** Loads a filtered page of menu items from the local catalog. */
    override suspend fun getRestaurantMenuPage(
        restaurantId: String,
        request: PageRequest,
        filter: MenuPageFilter
    ): AppResult<PagedResult<FoodItem>> = localPagingDataSource.loadMenuPage(
        restaurantId = restaurantId,
        request = request,
        filter = filter
    )

    /** Saves a sufficiently long search query in this user's recent history. */
    override suspend fun saveSearchQuery(query: String) = withContext(dispatchers.io) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        searchHistoryDao.upsertQuery(
            SearchHistoryEntity(userId = userId, query = trimmed, searchedAtMs = System.currentTimeMillis())
        )
    }

    /** Removes recent search history for the current user. */
    override suspend fun clearSearchHistory() = withContext(dispatchers.io) {
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        searchHistoryDao.clearForUser(userId)
    }

    /** Fetches restaurants and updates Room without discarding cached data on failure. */
    override suspend fun refreshHomeCatalog(): AppResult<Long> = withContext(dispatchers.io) {
        when (val result = restaurantRemote.fetchRestaurants()) {
            is AppResult.Success -> {
                val now = System.currentTimeMillis()
                catalogDao.upsertRestaurants(result.data.map { it.toEntity(now) })
                preferencesDataStore.updateLastCatalogSync(now)
                AppResult.Success(now)
            }
            is AppResult.Error -> {
                // Deliberately preserve cached Room restaurants on failure!
                result
            }
        }
    }

    /** Fetches a restaurant menu and saves it locally, preserving cache on failure. */
    override suspend fun refreshRestaurantMenu(restaurantId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            when (val result = restaurantRemote.fetchMenu(restaurantId)) {
                is AppResult.Success -> {
                    catalogDao.upsertFoodItems(result.data.map { it.toEntity(json) })
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> {
                    // Preserve cached menu for offline browsing
                    result
                }
            }
        }

    /** Changes favorite state locally and queues a retryable sync if remote sync fails. */
    override suspend fun toggleFavorite(restaurantId: String): AppResult<Boolean> =
        withContext(dispatchers.io) {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val now = System.currentTimeMillis()
            val currentlyFav = favoriteDao.isFavorite(userId, restaurantId)
            val newState = !currentlyFav

            if (newState) {
                favoriteDao.upsertFavorite(
                    FavoriteEntity(
                        userId = userId,
                        restaurantId = restaurantId,
                        syncStatus = "PENDING",
                        updatedAtMs = now
                    )
                )
            } else {
                favoriteDao.deleteFavorite(userId, restaurantId)
            }

            // Attempt immediate remote sync; if offline/failed, persist to transactional outbox
            when (userRemote.syncFavorite(userId, restaurantId, newState)) {
                is AppResult.Success -> {
                    if (newState) {
                        favoriteDao.upsertFavorite(
                            FavoriteEntity(userId, restaurantId, "SYNCED", now)
                        )
                    }
                    AppResult.Success(newState)
                }
                is AppResult.Error -> {
                    pendingOperationDao.upsert(
                        PendingOperationEntity(
                            operationId = "op_fav_${UUID.randomUUID().toString().take(10)}",
                            userId = userId,
                            entityType = "FAVORITE",
                            operationType = if (newState) "ADD_FAV" else "REMOVE_FAV",
                            itemId = restaurantId,
                            restaurantId = restaurantId,
                            quantity = if (newState) 1 else 0,
                            expectedUnitPrice = 0.0,
                            payloadJson = "",
                            status = "PENDING",
                            createdAtMs = now,
                            updatedAtMs = now
                        )
                    )
                    cartRepository.scheduleDeferredSync()
                    AppResult.Success(newState)
                }
            }
        }

    /** Looks up a coupon in Room and converts it to a domain object. */
    override suspend fun getCouponByCode(code: String): Coupon? = withContext(dispatchers.io) {
        catalogDao.getCouponByCode(code.trim())?.toDomain()
    }
}
