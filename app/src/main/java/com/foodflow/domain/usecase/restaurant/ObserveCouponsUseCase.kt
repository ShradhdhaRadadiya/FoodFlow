package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.Coupon
import com.foodflow.domain.repository.RestaurantRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveCouponsUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Provides live updates to the available discount coupons. */
    operator fun invoke(): Flow<List<Coupon>> = restaurantRepository.observeCoupons()
}
