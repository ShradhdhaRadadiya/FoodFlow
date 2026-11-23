package com.foodflow.domain.usecase.address

import com.foodflow.domain.repository.AddressRepository
import javax.inject.Inject

class SelectAddressUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Marks the saved address with this ID as the current delivery address. */
    suspend operator fun invoke(addressId: String) = addressRepository.selectAddress(addressId)
}
