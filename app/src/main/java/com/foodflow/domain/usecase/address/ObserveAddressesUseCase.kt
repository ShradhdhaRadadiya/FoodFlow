package com.foodflow.domain.usecase.address

import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.repository.AddressRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAddressesUseCase @Inject constructor(
    private val addressRepository: AddressRepository
) {
    /** Provides a live stream of the user's saved delivery addresses. */
    operator fun invoke(): Flow<List<DeliveryAddress>> = addressRepository.observeAddresses()
}
