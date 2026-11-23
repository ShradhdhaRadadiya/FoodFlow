package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class GetRestaurantOperatingHoursUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Loads the operating hours used to check available schedule times. */
    suspend operator fun invoke(restaurantId: String): RestaurantOperatingHours =
        scheduledOrderRepository.getRestaurantOperatingHours(restaurantId)
}
