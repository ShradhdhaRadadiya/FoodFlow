package com.foodflow.data.mock.scheduled

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.data.local.dao.AddressDao
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.remote.api.RestaurantAvailabilityDataSource
import com.foodflow.data.remote.dto.RestaurantAvailabilityResponseDto
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaceable mock implementation checking restaurant operating hours, open/paused status,
 * delivery address serviceability, and delivery slot capacity.
 */
@Singleton
class MockRestaurantAvailabilityDataSourceImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val addressDao: AddressDao,
    private val scenarioController: MockScenarioController,
    private val connectivityObserver: ConnectivityObserver
) : RestaurantAvailabilityDataSource {

    /** Checks whether the restaurant, address, and requested time can accept delivery. */
    override suspend fun checkAvailability(
        restaurantId: String,
        addressId: String,
        targetSlotUtcMs: Long
    ): AppResult<RestaurantAvailabilityResponseDto> {
        val cfg = scenarioController.current()
        if (cfg.forcedOffline || !connectivityObserver.isCurrentlyOnline()) {
            return AppResult.Error(AppError.Offline())
        }
        if (cfg.networkFault == SimulatedNetworkFault.TIMEOUT) {
            return AppResult.Error(AppError.Timeout())
        }
        if (cfg.networkFault == SimulatedNetworkFault.SERVER_ERROR_500) {
            return AppResult.Error(AppError.ServerError(500))
        }

        val restaurant = catalogDao.getRestaurant(restaurantId)
            ?: return AppResult.Error(AppError.EmptyResponse("Restaurant $restaurantId is no longer available."))
        val address = addressDao.getAddress(addressId)

        val openHour = DEFAULT_OPEN_HOUR
        val openMinute = DEFAULT_OPEN_MINUTE
        val closeHour = DEFAULT_CLOSE_HOUR
        val closeMinute = DEFAULT_CLOSE_MINUTE
        val prepLeadMinutes = (restaurant.deliveryTimeMin / 2).coerceIn(15, 30)

        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = targetSlotUtcMs }
        val slotMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val withinHours = slotMinutes in (openHour * 60 + openMinute)..(closeHour * 60 + closeMinute)

        val isOpen = restaurant.isOpen
        val isPaused = cfg.restaurantRejectsOrder
        val isAccepting = isOpen && !isPaused
        val addressServiceable = address?.isServiceable ?: true
        val deliveryCapacityAvailable = !cfg.delayedOrderActive || true
        val slotAvailable = withinHours && addressServiceable && deliveryCapacityAvailable

        val reason = when {
            !isOpen -> "${restaurant.name} is currently closed."
            isPaused -> "${restaurant.name} has temporarily paused accepting new orders."
            !withinHours -> "Selected time falls outside ${restaurant.name}'s operating hours ($openHour:00 AM – 11:00 PM)."
            !addressServiceable -> "Delivery is currently unavailable for the selected address."
            else -> null
        }

        return AppResult.Success(
            RestaurantAvailabilityResponseDto(
                restaurantId = restaurantId,
                isOpen = isOpen,
                isAcceptingOrders = isAccepting,
                isTemporarilyPaused = isPaused,
                supportsDeliveryAddress = addressServiceable,
                deliverySlotAvailable = slotAvailable,
                deliveryCapacityAvailable = deliveryCapacityAvailable,
                openHour = openHour,
                openMinute = openMinute,
                closeHour = closeHour,
                closeMinute = closeMinute,
                preparationLeadMinutes = prepLeadMinutes,
                estimatedDeliveryMinutes = if (cfg.delayedOrderActive) restaurant.deliveryTimeMin + 15 else restaurant.deliveryTimeMin,
                unavailableReason = reason
            )
        )
    }

    companion object {
        const val DEFAULT_OPEN_HOUR = 8
        const val DEFAULT_OPEN_MINUTE = 0
        const val DEFAULT_CLOSE_HOUR = 23
        const val DEFAULT_CLOSE_MINUTE = 0
    }
}
