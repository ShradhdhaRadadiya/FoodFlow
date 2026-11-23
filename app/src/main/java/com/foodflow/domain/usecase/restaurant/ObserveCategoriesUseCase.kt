package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveCategoriesUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates to the available food categories. */
    operator fun invoke(): Flow<List<FoodCategory>> = restaurantRepository.observeCategories()
}
