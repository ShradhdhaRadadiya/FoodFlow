package com.foodflow.domain.usecase.restaurant

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class RefreshRestaurantMenuUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Refreshes the saved menu for the specified restaurant. */
    suspend operator fun invoke(restaurantId: String): AppResult<Unit> =
        restaurantRepository.refreshRestaurantMenu(restaurantId)
}
