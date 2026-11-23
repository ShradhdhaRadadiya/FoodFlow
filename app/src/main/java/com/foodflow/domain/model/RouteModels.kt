package com.foodflow.domain.model

/**
 * Pure Kotlin geographic coordinate used for road-following delivery routes and map pickers.
 */
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Road-following polyline between restaurant pickup and customer doorstep.
 */
data class DeliveryRoute(
    val points: List<RouteCoordinate>,
    val distanceText: String,
    val durationText: String,
    val isRoadDirections: Boolean
)

/**
 * Interpolated rider state along a [DeliveryRoute] polyline, including compass heading
 * in degrees (0° = North, 90° = East, 180° = South, 270° = West) for direction-wise bike rotation.
 */
data class RouteProgressSample(
    val position: RouteCoordinate,
    val bearingDegrees: Float,
    val completedPoints: List<RouteCoordinate>,
    val remainingPoints: List<RouteCoordinate>
)

/**
 * Searchable neighborhood or geocoded address suggestion for the interactive map picker.
 */
data class MapLocationSuggestion(
    val title: String,
    val subtitle: String,
    val line1: String,
    val landmark: String,
    val city: String,
    val pincode: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Confirmed delivery pin selected from the interactive Google Maps picker.
 */
data class SelectedMapPin(
    val line1: String,
    val landmark: String,
    val city: String,
    val pincode: String,
    val latitude: Double,
    val longitude: Double
)
