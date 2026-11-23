package com.foodflow.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Customer review associated with a completed FoodFlow order and restaurant.
 */
data class RestaurantReview(
    val id: String,
    val restaurantId: String,
    val orderId: String,
    val userId: String,
    val userName: String,
    val rating: Int,
    val comment: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Authoritative aggregate rating summary for a restaurant.
 */
data class RestaurantRatingSummary(
    val restaurantId: String,
    val averageRating: Double,
    val totalReviewCount: Int,
    val ratingSum: Double = averageRating * totalReviewCount
)

/**
 * Pure domain utility for exact restaurant rating aggregation and formatting.
 * Ensures zero floating-point drift when adding or updating customer reviews.
 */
object RestaurantRatingCalculator {

    /** Returns the new average rating and review count after adding a review. */
    fun calculateAfterNewReview(
        currentRatingSum: Double,
        currentTotalReviews: Int,
        newRating: Int
    ): Pair<Double, Int> {
        val clampedRating = newRating.coerceIn(1, 5)
        val safeCount = currentTotalReviews.coerceAtLeast(0)
        val safeSum = if (safeCount == 0) 0.0 else currentRatingSum.coerceAtLeast(0.0)
        val nextCount = safeCount + 1
        val nextSum = safeSum + clampedRating
        return roundToTwoDecimals(nextSum / nextCount) to nextCount
    }

    /** Returns the new average rating and unchanged review count after editing a review. */
    fun calculateAfterUpdatedReview(
        currentRatingSum: Double,
        currentTotalReviews: Int,
        previousRating: Int,
        updatedRating: Int
    ): Pair<Double, Int> {
        val safeCount = currentTotalReviews.coerceAtLeast(1)
        val clampedOld = previousRating.coerceIn(1, 5)
        val clampedNew = updatedRating.coerceIn(1, 5)
        val nextSum = (currentRatingSum - clampedOld + clampedNew).coerceAtLeast(1.0)
        return roundToTwoDecimals(nextSum / safeCount) to safeCount
    }

    /** Rounds a rating to two decimal places and keeps it within the valid range. */
    fun roundToTwoDecimals(value: Double): Double {
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return 0.0
        return BigDecimal.valueOf(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceIn(0.0, 5.0)
    }

    /**
     * Formats an average rating concisely:
     * - 0 reviews -> "New"
     * - 4.20 -> "4.2"
     * - 4.21 -> "4.21"
     */
    fun formatRating(rating: Double, reviewCount: Int? = null): String {
        if (reviewCount != null && reviewCount <= 0) return "New"
        if (rating <= 0.0) return "New"
        val rounded = roundToTwoDecimals(rating)
        val hundredths = ((rounded * 100.0).toInt()) % 10
        return if (hundredths == 0) {
            String.format(java.util.Locale.US, "%.1f", rounded)
        } else {
            String.format(java.util.Locale.US, "%.2f", rounded)
        }
    }
}
