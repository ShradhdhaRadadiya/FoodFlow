package com.foodflow.domain.usecase.restaurant

import com.foodflow.domain.model.Coupon
import com.foodflow.domain.repository.RestaurantRepository
import javax.inject.Inject

class GetCouponByCodeUseCase @Inject constructor(
    private val restaurantRepository: RestaurantRepository
) {
    /** Looks up a coupon by code, returning null when no matching coupon exists. */
    suspend operator fun invoke(code: String): Coupon? = restaurantRepository.getCouponByCode(code)
}
