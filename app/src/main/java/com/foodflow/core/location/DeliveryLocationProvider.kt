package com.foodflow.core.location

import com.foodflow.domain.model.RiderLocation
import com.foodflow.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event-driven delivery partner location provider abstraction.
 *
 * Isolates live coordinate streaming behind a clean domain boundary so Google Maps UI
 * observes real-time updates persisted from the WebSocket tracking pipeline (and can be
 * backed by any real-time partner telemetry service without modifying UI composables).
 */
interface DeliveryLocationProvider {
    /** Observes the latest persisted rider position, or null when the order has none. */
    fun observeDeliveryLocation(orderId: String): Flow<RiderLocation?>
}

@Singleton
class RoomDeliveryLocationProvider @Inject constructor(
    private val orderRepository: OrderRepository
) : DeliveryLocationProvider {

    /** Maps order updates to rider coordinates and supplies a restaurant-origin fallback before tracking starts. */
    override fun observeDeliveryLocation(orderId: String): Flow<RiderLocation?> =
        orderRepository.observeOrder(orderId)
            .map { order ->
                order?.riderLocation ?: order?.let {
                    RiderLocation(
                        latitude = it.restaurantLat,
                        longitude = it.restaurantLng,
                        progressFraction = 0f,
                        etaMinutes = it.estimatedDeliveryMinutes,
                        updatedAtMs = it.updatedAtMs
                    )
                }
            }
            .distinctUntilChanged()
}
