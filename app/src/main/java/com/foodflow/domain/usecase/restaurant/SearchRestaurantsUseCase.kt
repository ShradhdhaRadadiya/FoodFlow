package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchRestaurantsUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Searches locally saved restaurants and streams matching results. */
    operator fun invoke(query: String): Flow<List<Restaurant>> =
        restaurantRepository.searchRestaurantsLocal(query)
}
