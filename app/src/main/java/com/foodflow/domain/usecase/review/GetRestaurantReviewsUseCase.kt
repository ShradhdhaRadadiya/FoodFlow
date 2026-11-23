package com.foodflow.domain.usecase.review

import com.foodflow.domain.model.RestaurantReview
import com.foodflow.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRestaurantReviewsUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    /** Provides live customer reviews for the specified restaurant. */
    operator fun invoke(restaurantId: String): Flow<List<RestaurantReview>> =
        reviewRepository.observeRestaurantReviews(restaurantId)
}
