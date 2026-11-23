package com.foodflow.domain.usecase.review

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.repository.ReviewRepository
import javax.inject.Inject

class RefreshRestaurantReviewsUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    /** Fetches the latest reviews and rating summary for this restaurant. */
    suspend operator fun invoke(restaurantId: String): AppResult<RestaurantRatingSummary> =
        reviewRepository.refreshRestaurantReviews(restaurantId)
}
