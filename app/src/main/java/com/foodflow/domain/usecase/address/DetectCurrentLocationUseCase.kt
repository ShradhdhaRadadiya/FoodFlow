package com.foodflow.domain.usecase.address

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.repository.AddressRepository
import javax.inject.Inject

class DetectCurrentLocationUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Gets the app's current-location address and returns the result. */
    suspend operator fun invoke(): AppResult<DeliveryAddress> =
        addressRepository.simulateCurrentLocation()
}
