package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.DeliveryAddress
import kotlinx.coroutines.flow.Flow

interface AddressRepository {
    /** Streams the user's saved delivery addresses. */
    fun observeAddresses(): Flow<List<DeliveryAddress>>
    /** Streams the address currently selected for delivery. */
    fun observeSelectedAddress(): Flow<DeliveryAddress?>
    /** Saves which address should be used for delivery. */
    suspend fun selectAddress(addressId: String)
    /** Validates and saves a delivery address. */
    suspend fun saveAddress(address: DeliveryAddress): AppResult<DeliveryAddress>
    /** Deletes the saved address with this ID. */
    suspend fun deleteAddress(addressId: String): AppResult<Unit>
    /** Returns and saves the app's current-location address. */
    suspend fun simulateCurrentLocation(): AppResult<DeliveryAddress>
}
