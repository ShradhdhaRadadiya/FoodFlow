package com.foodflow.domain.usecase.address

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.AddressRepository
import javax.inject.Inject

class DeleteAddressUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Deletes the saved delivery address with this ID. */
    suspend operator fun invoke(addressId: String): AppResult<Unit> =
        addressRepository.deleteAddress(addressId)
}
