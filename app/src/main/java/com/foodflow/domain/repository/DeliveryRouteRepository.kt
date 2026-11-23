package com.foodflow.domain.repository

import com.foodflow.domain.model.DeliveryRoute
import com.foodflow.domain.model.RouteCoordinate

/**
 * Provides road-following delivery routes between restaurant pickup and customer doorstep,
 * preferring Google Directions API when configured and falling back to realistic road waypoints.
 */
interface DeliveryRouteRepository {
    /** Returns a route between the restaurant and delivery destination. */
    suspend fun getDeliveryRoute(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ): DeliveryRoute
}
