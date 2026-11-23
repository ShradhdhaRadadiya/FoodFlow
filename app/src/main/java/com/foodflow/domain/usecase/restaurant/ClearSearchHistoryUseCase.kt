package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class ClearSearchHistoryUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Removes the user's saved recent search queries. */
    suspend operator fun invoke() = restaurantRepository.clearSearchHistory()
}
