package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.model.RestaurantReview
import kotlinx.coroutines.flow.Flow

interface ReviewRepository {
    /** Streams the reviews saved for a restaurant. */
    fun observeRestaurantReviews(restaurantId: String): Flow<List<RestaurantReview>>
    /** Streams the saved rating average and review count for a restaurant. */
    fun observeRestaurantRating(restaurantId: String): Flow<RestaurantRatingSummary?>
    /** Streams the review associated with an order, if one exists. */
    fun observeReviewForOrder(orderId: String): Flow<RestaurantReview?>

    /** Saves a review for a completed order and updates the restaurant rating. */
    suspend fun submitReview(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary>

    /** Edits an existing order review and recalculates the restaurant rating. */
    suspend fun updateReview(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary>

    /** Refreshes a restaurant's reviews and rating from the data source. */
    suspend fun refreshRestaurantReviews(restaurantId: String): AppResult<RestaurantRatingSummary>
}
