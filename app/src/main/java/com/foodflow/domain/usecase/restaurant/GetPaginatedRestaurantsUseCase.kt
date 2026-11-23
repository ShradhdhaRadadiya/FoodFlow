package com.foodflow.domain.usecase.restaurant

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.PageRequest
import com.foodflow.domain.model.PagedResult
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantPageFilter
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class GetPaginatedRestaurantsUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Loads one page of restaurants that match the supplied filters. */
    suspend operator fun invoke(
        request: PageRequest,
        filter: RestaurantPageFilter = RestaurantPageFilter()
    ): AppResult<PagedResult<Restaurant>> = restaurantRepository.getRestaurantsPage(request, filter)
}
