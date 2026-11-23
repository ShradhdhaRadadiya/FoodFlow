package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveRecentSearchesUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates to the user's recent search terms. */
    operator fun invoke(): Flow<List<String>> = restaurantRepository.observeRecentSearches()
}
