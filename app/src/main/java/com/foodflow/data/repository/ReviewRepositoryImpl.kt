package com.foodflow.data.repository

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.dao.ReviewDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.data.remote.api.ReviewRemoteDataSource
import com.foodflow.data.remote.dto.SubmitReviewRequestDto
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.model.RestaurantReview
import com.foodflow.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for restaurant reviews and dynamic rating synchronization.
 *
 * Every screen (Home, Restaurant Listing, Search, Favorites, Restaurant Detail, Orders)
 * observes Room's `restaurants`, `restaurant_reviews`, and `orders` tables. Whenever a
 * review is submitted or edited, the authoritative backend aggregate is persisted atomically
 * via [ReviewDao.saveReviewAndSyncAggregate], propagating immediately to every active screen
 * without duplicate data sources or stale UI state.
 */
@Singleton
class ReviewRepositoryImpl @Inject constructor(
    private val reviewDao: ReviewDao,
    private val orderDao: OrderDao,
    private val catalogDao: CatalogDao,
    private val reviewRemote: ReviewRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val dispatchers: DispatcherProvider
) : ReviewRepository {

    private val reviewMutex = Mutex()

    /** Streams saved reviews for this restaurant as domain objects. */
    override fun observeRestaurantReviews(restaurantId: String): Flow<List<RestaurantReview>> =
        reviewDao.observeReviewsForRestaurant(restaurantId).map { entities ->
            entities.map { it.toDomain() }
        }

    /** Streams the saved restaurant rating average and review count. */
    override fun observeRestaurantRating(restaurantId: String): Flow<RestaurantRatingSummary?> =
        catalogDao.observeRestaurant(restaurantId).map { entity ->
            entity?.let {
                RestaurantRatingSummary(
                    restaurantId = it.id,
                    averageRating = it.rating,
                    totalReviewCount = it.ratingCount
                )
            }
        }

    /** Streams the saved review associated with one order. */
    override fun observeReviewForOrder(orderId: String): Flow<RestaurantReview?> =
        reviewDao.observeReviewForOrder(orderId).map { it?.toDomain() }

    /** Submits a new review after validating the delivered order and network state. */
    override suspend fun submitReview(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary> = submitOrUpdateReview(
        orderId = orderId,
        rating = rating,
        comment = comment,
        allowUpdate = false
    )

    /** Updates an existing review and synchronizes its rating aggregate. */
    override suspend fun updateReview(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary> = submitOrUpdateReview(
        orderId = orderId,
        rating = rating,
        comment = comment,
        allowUpdate = true
    )

    /** Validates and sends a review, then saves the review and rating together in Room. */
    private suspend fun submitOrUpdateReview(
        orderId: String,
        rating: Int,
        comment: String,
        allowUpdate: Boolean
    ): AppResult<RestaurantRatingSummary> = withContext(dispatchers.io) {
        try {
            if (rating !in 1..5) {
                return@withContext AppResult.Error(
                    AppError.Validation("Please select a rating between 1 and 5 stars.")
                )
            }

            if (!connectivityObserver.isCurrentlyOnline()) {
                return@withContext AppResult.Error(
                    AppError.Offline(
                        userMessage = "You are offline. Reconnect to submit your review — your restaurant rating was not changed."
                    )
                )
            }

            reviewMutex.withLock {
                val order = orderDao.getOrder(orderId)
                    ?: return@withLock AppResult.Error(AppError.Validation("Order not found."))

                if (order.orderStatus != OrderStatus.DELIVERED.name) {
                    return@withLock AppResult.Error(
                        AppError.Validation("Only delivered orders can be rated.")
                    )
                }

                val existingLocalReview = reviewDao.getReviewByOrderId(orderId)
                if (!allowUpdate && (existingLocalReview != null || order.ratingGiven != null)) {
                    return@withLock AppResult.Error(
                        AppError.Conflict(
                            userMessage = "You have already rated this order. Use Edit Rating to update your review."
                        )
                    )
                }

                val snapshot = preferencesDataStore.getSnapshot()
                val userId = snapshot.userId ?: DatabaseSeeder.DEFAULT_USER_ID
                val userName = snapshot.userName?.takeIf { it.isNotBlank() } ?: "FoodFlow Diner"
                val trimmedComment = comment.trim()

                val idempotencyKey = if (allowUpdate) {
                    "rev_upd_${orderId}_${userId}_${rating}_${trimmedComment.hashCode()}"
                } else {
                    "rev_new_${orderId}_${userId}"
                }

                val request = SubmitReviewRequestDto(
                    idempotencyKey = idempotencyKey,
                    restaurantId = order.restaurantId,
                    orderId = orderId,
                    userId = userId,
                    userName = userName,
                    rating = rating,
                    comment = trimmedComment
                )

                when (val remoteRes = reviewRemote.submitReview(request, allowUpdate = allowUpdate)) {
                    is AppResult.Success -> {
                        val response = remoteRes.data
                        val reviewEntity = response.review.toEntity()
                        val aggregate = response.updatedAggregate
                        reviewDao.saveReviewAndSyncAggregate(
                            review = reviewEntity,
                            newAverageRating = aggregate.averageRating,
                            newTotalReviewCount = aggregate.totalReviewCount,
                            updatedAtMs = response.review.updatedAtMs
                        )
                        AppResult.Success(
                            RestaurantRatingSummary(
                                restaurantId = aggregate.restaurantId,
                                averageRating = aggregate.averageRating,
                                totalReviewCount = aggregate.totalReviewCount
                            )
                        )
                    }
                    is AppResult.Error -> remoteRes
                }
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Fetches the latest reviews and rating summary and updates their local cache. */
    override suspend fun refreshRestaurantReviews(
        restaurantId: String
    ): AppResult<RestaurantRatingSummary> = withContext(dispatchers.io) {
        try {
            when (val remoteRes = reviewRemote.fetchRestaurantReviews(restaurantId)) {
                is AppResult.Success -> {
                    val data = remoteRes.data
                    val now = System.currentTimeMillis()
                    if (data.reviews.isNotEmpty()) {
                        reviewDao.upsertReviews(data.reviews.map { it.toEntity() })
                    }
                    reviewDao.updateRestaurantRatingAggregate(
                        restaurantId = data.aggregate.restaurantId,
                        rating = data.aggregate.averageRating,
                        ratingCount = data.aggregate.totalReviewCount,
                        updatedAtMs = now
                    )
                    AppResult.Success(
                        RestaurantRatingSummary(
                            restaurantId = data.aggregate.restaurantId,
                            averageRating = data.aggregate.averageRating,
                            totalReviewCount = data.aggregate.totalReviewCount
                        )
                    )
                }
                is AppResult.Error -> remoteRes
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }
}
