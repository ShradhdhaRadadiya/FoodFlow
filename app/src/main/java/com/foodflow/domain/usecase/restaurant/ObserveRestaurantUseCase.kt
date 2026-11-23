package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveRestaurantUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates for one restaurant, or null if it is unavailable. */
    operator fun invoke(restaurantId: String): Flow<Restaurant?> =
        restaurantRepository.observeRestaurant(restaurantId)
}
