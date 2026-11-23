package com.foodflow.domain.usecase.review

import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRestaurantRatingUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    /** Provides live rating totals for the specified restaurant. */
    operator fun invoke(restaurantId: String): Flow<RestaurantRatingSummary?> =
        reviewRepository.observeRestaurantRating(restaurantId)
}
