package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveRestaurantsUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates to the restaurant catalog. */
    operator fun invoke(): Flow<List<Restaurant>> = restaurantRepository.observeRestaurants()
}
