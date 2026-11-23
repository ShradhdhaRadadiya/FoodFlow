package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.repository.OrderTrackingRepository
import javax.inject.Inject

class DismissDeliveredOrderBannerUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Records that the user dismissed the delivered-order banner. */
    operator fun invoke() = orderTrackingRepository.dismissDeliveredOrderBanner()
}
