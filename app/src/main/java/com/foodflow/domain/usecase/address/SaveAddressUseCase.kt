package com.foodflow.domain.usecase.address

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.repository.AddressRepository
import javax.inject.Inject

class SaveAddressUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Saves the supplied delivery address and returns the saved result. */
    suspend operator fun invoke(address: DeliveryAddress): AppResult<DeliveryAddress> =
        addressRepository.saveAddress(address)
}
