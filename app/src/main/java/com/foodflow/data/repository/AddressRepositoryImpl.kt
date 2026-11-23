package com.foodflow.data.repository

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.AddressDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.repository.AddressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AddressRepositoryImpl @Inject constructor(
    private val addressDao: AddressDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val dispatchers: DispatcherProvider
) : AddressRepository {

    /** Continuously provides this user's saved delivery addresses from the local database. */
    override fun observeAddresses(): Flow<List<DeliveryAddress>> =
        preferencesDataStore.preferences.flatMapLatest { prefs ->
            val userId = prefs.userId ?: DatabaseSeeder.DEFAULT_USER_ID
            addressDao.observeAddresses(userId).map { list -> list.map { it.toDomain() } }
        }

    /** Watches saved addresses and returns the selected one, falling back to the default or first address. */
    override fun observeSelectedAddress(): Flow<DeliveryAddress?> =
        combine(observeAddresses(), preferencesDataStore.preferences) { addresses, prefs ->
            addresses.firstOrNull { it.id == prefs.selectedAddressId }
                ?: addresses.firstOrNull { it.isDefault }
                ?: addresses.firstOrNull()
        }

    /** Saves which delivery address the app should use as the current selection. */
    override suspend fun selectAddress(addressId: String) = withContext(dispatchers.io) {
        preferencesDataStore.updateSelectedAddress(addressId)
    }

    /** Validates and stores an address, then selects it when it can receive deliveries. */
    override suspend fun saveAddress(address: DeliveryAddress): AppResult<DeliveryAddress> =
        withContext(dispatchers.io) {
            try {
                if (address.line1.isBlank() || address.city.isBlank()) {
                    return@withContext AppResult.Error(AppError.Validation("Please fill street address and city."))
                }
                val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
                val isServiceable = !address.pincode.startsWith("3821") &&
                    !address.city.equals("Sanand", ignoreCase = true)
                val finalAddress = address.copy(
                    id = address.id.ifBlank { "addr_${UUID.randomUUID().toString().take(8)}" },
                    isServiceable = isServiceable
                )
                if (finalAddress.isDefault) {
                    addressDao.clearDefaultForUser(userId)
                }
                addressDao.upsertAddress(finalAddress.toEntity(userId))
                if (finalAddress.isServiceable) {
                    preferencesDataStore.updateSelectedAddress(finalAddress.id)
                }
                AppResult.Success(finalAddress)
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Removes the saved address with the given ID and reports whether the operation succeeded. */
    override suspend fun deleteAddress(addressId: String): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            addressDao.deleteAddress(addressId)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Stores the app's sample current-location address and makes it the selected address. */
    override suspend fun simulateCurrentLocation(): AppResult<DeliveryAddress> = withContext(dispatchers.io) {
        try {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val detected = DeliveryAddress(
                id = "addr_gps_current",
                label = AddressLabel.OTHER,
                recipientName = preferencesDataStore.getSnapshot().userName,
                line1 = "Near Sindhu Bhavan Circle, Bodakdev",
                landmark = "Current GPS Location",
                city = "Ahmedabad",
                pincode = "380054",
                deliveryInstructions = "Call on arrival",
                latitude = 23.0432,
                longitude = 72.5194,
                isDefault = false,
                isServiceable = true
            )
            addressDao.upsertAddress(detected.toEntity(userId))
            preferencesDataStore.updateSelectedAddress(detected.id)
            AppResult.Success(detected)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }
}
