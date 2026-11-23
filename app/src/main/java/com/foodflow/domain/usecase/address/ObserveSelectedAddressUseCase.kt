package com.foodflow.domain.usecase.address

import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.repository.AddressRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSelectedAddressUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Provides a live stream of the currently selected delivery address. */
    operator fun invoke(): Flow<DeliveryAddress?> = addressRepository.observeSelectedAddress()
}
