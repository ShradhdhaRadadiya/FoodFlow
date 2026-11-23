package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveMenuUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates to the menu for the specified restaurant. */
    operator fun invoke(restaurantId: String): Flow<List<FoodItem>> =
        restaurantRepository.observeMenu(restaurantId)
}
