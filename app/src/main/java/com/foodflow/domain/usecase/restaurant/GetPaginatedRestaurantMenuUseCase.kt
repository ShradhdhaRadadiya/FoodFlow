package com.foodflow.domain.usecase.restaurant

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.MenuPageFilter
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class GetPaginatedRestaurantMenuUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Loads one filtered page of menu items for the selected restaurant. */
    suspend operator fun invoke(
        restaurantId: String,
        request: PageRequest,
        filter: MenuPageFilter = MenuPageFilter()
    ): AppResult<PagedResult<FoodItem>> =
        restaurantRepository.getRestaurantMenuPage(restaurantId, request, filter)
}
