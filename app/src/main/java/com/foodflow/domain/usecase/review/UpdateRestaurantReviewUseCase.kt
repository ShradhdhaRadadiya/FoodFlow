package com.foodflow.domain.usecase.review

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.repository.ReviewRepository
import javax.inject.Inject

class UpdateRestaurantReviewUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    /** Updates the review already submitted for the specified order. */
    suspend operator fun invoke(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary> =
        reviewRepository.updateReview(orderId = orderId, rating = rating, comment = comment)
}
