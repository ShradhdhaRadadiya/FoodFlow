package com.foodflow.domain.usecase.review

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.RestaurantRatingSummary
import com.foodflow.domain.repository.ReviewRepository
import javax.inject.Inject

class SubmitRestaurantReviewUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    /** Submits a new review for a completed order. */
    suspend operator fun invoke(
        orderId: String,
        rating: Int,
        comment: String
    ): AppResult<RestaurantRatingSummary> =
        reviewRepository.submitReview(orderId = orderId, rating = rating, comment = comment)
}
