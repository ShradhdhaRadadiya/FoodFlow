package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchFoodItemsUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Searches locally saved food items and streams matching results. */
    operator fun invoke(query: String): Flow<List<FoodItem>> =
        restaurantRepository.searchFoodItemsLocal(query)
}
