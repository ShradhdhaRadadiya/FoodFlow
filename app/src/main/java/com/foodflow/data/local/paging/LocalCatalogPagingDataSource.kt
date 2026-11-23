package com.foodflow.data.local.paging

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.FavoriteDao
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.model.RestaurantSortOption
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Room-backed simulated pagination data source for restaurant & menu catalogs.
 *
 * Architectural note: FoodFlow currently runs on a local Room + seeded catalog without a remote
 * cursor/page API. This data source implements deterministic, deduplicated page slicing over Room
 * so the Presentation and Domain layers already use clean incremental pagination contracts and can
 * seamlessly swap to server-backed cursor/page endpoints once a backend API is wired.
 */
@Singleton
class LocalCatalogPagingDataSource @Inject constructor(
    private val catalogDao: CatalogDao,
    private val favoriteDao: FavoriteDao,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) {

    /** Filters, sorts, and returns one page of restaurants from the local Room catalog. */
    suspend fun loadRestaurantsPage(
        userId: String,
        request: PageRequest,
        filter: RestaurantPageFilter
    ): AppResult<PagedResult<Restaurant>> = withContext(dispatchers.io) {
        try {
            val favSet = favoriteDao.getFavoriteIds(userId).toSet()
            val all = catalogDao.getAllRestaurantsSnapshot()
                .map { it.toDomain(isFavorite = it.id in favSet) }
                .distinctBy { it.id }

            val filtered = all.filter { r ->
                val catOk = filter.category.isNullOrBlank() ||
                    r.cuisines.any { it.contains(filter.category, ignoreCase = true) }
                val vegOk = !filter.pureVegOnly || r.isPureVeg
                val openOk = !filter.openOnly || r.isOpen
                catOk && vegOk && openOk
            }

            val ordered = when (filter.sortBy) {
                RestaurantSortOption.RELEVANCE -> filtered
                RestaurantSortOption.RATING -> filtered.sortedWith(
                    compareByDescending<Restaurant> { it.rating }.thenBy { it.id }
                )
                RestaurantSortOption.DELIVERY_TIME -> filtered.sortedWith(
                    compareBy<Restaurant> { it.deliveryTimeMin }.thenBy { it.id }
                )
                RestaurantSortOption.COST_LOW -> filtered.sortedWith(
                    compareBy<Restaurant> { it.priceForTwo }.thenBy { it.id }
                )
            }

            AppResult.Success(slicePage(ordered, request))
        } catch (t: Throwable) {
            AppResult.Error(
                AppError.DatabaseError(
                    userMessage = "Could not load more restaurants right now. Tap Retry to try again.",
                    technicalDetail = t.message ?: t.javaClass.simpleName
                )
            )
        }
    }

    /** Filters and returns one page of a restaurant's menu from Room. */
    suspend fun loadMenuPage(
        restaurantId: String,
        request: PageRequest,
        filter: MenuPageFilter
    ): AppResult<PagedResult<FoodItem>> = withContext(dispatchers.io) {
        try {
            val query = filter.searchQuery.trim()
            val all = catalogDao.getMenuSnapshot(restaurantId)
                .map { it.toDomain(json) }
                .distinctBy { it.id }

            val filtered = all.filter { item ->
                val vegOk = !filter.vegOnly || item.isVeg
                val queryOk = query.isBlank() ||
                    item.name.contains(query, ignoreCase = true) ||
                    item.category.contains(query, ignoreCase = true) ||
                    item.description.contains(query, ignoreCase = true)
                vegOk && queryOk
            }

            AppResult.Success(slicePage(filtered, request))
        } catch (t: Throwable) {
            AppResult.Error(
                AppError.DatabaseError(
                    userMessage = "Could not load more menu items right now. Tap Retry to try again.",
                    technicalDetail = t.message ?: t.javaClass.simpleName
                )
            )
        }
    }

    /** Slices an ordered list into a page and reports whether more items remain. */
    private fun <T> slicePage(
        orderedItems: List<T>,
        request: PageRequest
    ): PagedResult<T> {
        val total = orderedItems.size
        val start = request.offset.coerceAtMost(total)
        val end = (start + request.pageSize).coerceAtMost(total)
        val pageItems = if (start < end) orderedItems.subList(start, end) else emptyList()
        return PagedResult(
            items = pageItems,
            pageIndex = request.pageIndex,
            pageSize = request.pageSize,
            totalCount = total,
            hasNextPage = end < total
        )
    }
}
