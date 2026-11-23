package com.foodflow.data.repository

import com.example.BuildConfig
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.domain.model.DeliveryRoute
import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.repository.DeliveryRouteRepository
import com.foodflow.domain.route.RouteGeometryCalculator
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Resolves road-following routes via Google Directions API when [BuildConfig.MAPS_API_KEY]
 * is configured and the device is online, with an automatic deterministic urban road-waypoint
 * fallback so live delivery bike routing remains smooth and road-aligned even offline.
 */
@Singleton
class DeliveryRouteRepositoryImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : DeliveryRouteRepository {

    private val routeCache = ConcurrentHashMap<String, DeliveryRoute>()

    /** Returns a cached route, requests road directions online, or builds a local fallback. */
    override suspend fun getDeliveryRoute(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ): DeliveryRoute = withContext(dispatchers.io) {
        val cacheKey = String.format(
            Locale.US,
            "%.4f,%.4f->%.4f,%.4f",
            origin.latitude,
            origin.longitude,
            destination.latitude,
            destination.longitude
        )
        routeCache[cacheKey]?.let { return@withContext it }

        val apiKey = BuildConfig.MAPS_API_KEY
        val hasValidKey = apiKey.isNotBlank() && apiKey != "MAPS_API_KEY_DEFAULT_VALUE"

        val resolved = if (hasValidKey && connectivityObserver.isCurrentlyOnline()) {
            fetchGoogleDirectionsRoute(origin, destination, apiKey)
                ?: buildFallbackRoute(origin, destination)
        } else {
            buildFallbackRoute(origin, destination)
        }

        routeCache[cacheKey] = resolved
        resolved
    }

    /** Requests and decodes a driving route from Google Directions when possible. */
    private fun fetchGoogleDirectionsRoute(
        origin: RouteCoordinate,
        destination: RouteCoordinate,
        apiKey: String
    ): DeliveryRoute? = runCatching {
        val url = buildString {
            append("https://maps.googleapis.com/maps/api/directions/json?")
            append("origin=${origin.latitude},${origin.longitude}")
            append("&destination=${destination.latitude},${destination.longitude}")
            append("&mode=driving")
            append("&key=$apiKey")
        }
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@runCatching null
            val root = JSONObject(body)
            if (root.optString("status") != "OK") return@runCatching null
            val routes = root.optJSONArray("routes") ?: return@runCatching null
            if (routes.length() == 0) return@runCatching null

            val firstRoute = routes.getJSONObject(0)
            val encodedPolyline = firstRoute
                .optJSONObject("overview_polyline")
                ?.optString("points")
                .orEmpty()
            val decoded = RouteGeometryCalculator.decodeEncodedPolyline(encodedPolyline)
            if (decoded.size < 2) return@runCatching null

            val firstLeg = firstRoute.optJSONArray("legs")?.optJSONObject(0)
            val distText = firstLeg?.optJSONObject("distance")?.optString("text").orEmpty()
                .ifBlank { formatDistance(RouteGeometryCalculator.totalDistanceKm(decoded)) }
            val durText = firstLeg?.optJSONObject("duration")?.optString("text").orEmpty()
                .ifBlank { formatDuration(RouteGeometryCalculator.totalDistanceKm(decoded)) }

            DeliveryRoute(
                points = decoded,
                distanceText = distText,
                durationText = durText,
                isRoadDirections = true
            )
        }
    }.getOrNull()

    /** Builds a deterministic local road-style route when directions are unavailable. */
    private fun buildFallbackRoute(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ): DeliveryRoute {
        val waypoints = RouteGeometryCalculator.buildUrbanRoadFallback(origin, destination)
        val distanceKm = RouteGeometryCalculator.totalDistanceKm(waypoints)
        return DeliveryRoute(
            points = waypoints,
            distanceText = formatDistance(distanceKm),
            durationText = formatDuration(distanceKm),
            isRoadDirections = false
        )
    }

    /** Formats a route distance in kilometers for display. */
    private fun formatDistance(km: Double): String =
        String.format(Locale.US, "%.1f km", km.coerceAtLeast(0.4))

    /** Estimates and formats travel time from route distance. */
    private fun formatDuration(km: Double): String {
        val mins = ((km / 22.0) * 60.0).roundToInt().coerceAtLeast(5)
        return "$mins mins"
    }
}
