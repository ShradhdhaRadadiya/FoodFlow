package com.foodflow.domain.usecase.restaurant

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class RefreshHomeCatalogUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Refreshes the home restaurant catalog and returns its sync timestamp. */
    suspend operator fun invoke(): AppResult<Long> = restaurantRepository.refreshHomeCatalog()
}
