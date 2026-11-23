package com.foodflow.domain.usecase.tracking

import com.foodflow.core.location.DeliveryLocationProvider
import com.foodflow.domain.model.RiderLocation
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveDeliveryLocationUseCase @Inject constructor(
    private val deliveryLocationProvider: DeliveryLocationProvider
) {
    /** Provides live rider-location updates for the specified order. */
    operator fun invoke(orderId: String): Flow<RiderLocation?> =
        deliveryLocationProvider.observeDeliveryLocation(orderId)
}
