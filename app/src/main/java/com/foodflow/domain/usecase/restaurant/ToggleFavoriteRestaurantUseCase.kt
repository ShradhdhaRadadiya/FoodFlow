package com.foodflow.domain.usecase.restaurant

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class ToggleFavoriteRestaurantUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Adds or removes the restaurant from favorites and returns its new favorite state. */
    suspend operator fun invoke(restaurantId: String): AppResult<Boolean> =
        restaurantRepository.toggleFavorite(restaurantId)
}
