package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.model.DeliveryRoute
import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.repository.DeliveryRouteRepository
import javax.inject.Inject

/**
 * Resolves a road-following delivery route for live order tracking.
 */
class GetDeliveryRouteUseCase @Inject constructor(
    private val deliveryRouteRepository: DeliveryRouteRepository
) {
    /** Gets a route from the rider's origin to the delivery destination. */
    suspend operator fun invoke(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ): DeliveryRoute = deliveryRouteRepository.getDeliveryRoute(origin, destination)
}
