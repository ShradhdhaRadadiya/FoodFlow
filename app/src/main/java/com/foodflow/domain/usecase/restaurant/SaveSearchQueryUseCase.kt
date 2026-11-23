package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class SaveSearchQueryUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Adds the submitted search term to the user's recent-search history. */
    suspend operator fun invoke(query: String) = restaurantRepository.saveSearchQuery(query)
}
