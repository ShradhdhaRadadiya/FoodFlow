package com.foodflow.domain.route

import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.model.RouteProgressSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin geometry engine for Google Directions polyline decoding, realistic urban
 * road-waypoint fallback generation, cumulative distance sampling, and compass bearing
 * computation for direction-wise delivery bike rotation.
 */
object RouteGeometryCalculator {

    /**
     * Decodes a Google Encoded Polyline string into geographic [RouteCoordinate] points.
     */
    fun decodeEncodedPolyline(encoded: String): List<RouteCoordinate> {
        if (encoded.isBlank()) return emptyList()
        val coordinates = mutableListOf<RouteCoordinate>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dLng

            coordinates.add(RouteCoordinate(lat / 1E5, lng / 1E5))
        }
        return coordinates
    }

    /**
     * Generates a realistic multi-turn urban road route between [origin] and [destination]
     * following street-grid avenues with softly chamfered turns so live tracking never cuts
     * straight diagonally across city blocks when offline or without a Directions API key.
     */
    fun buildUrbanRoadFallback(
        origin: RouteCoordinate,
        destination: RouteCoordinate
    ): List<RouteCoordinate> {
        val dLat = destination.latitude - origin.latitude
        val dLng = destination.longitude - origin.longitude

        if (hypot(dLat, dLng) < 1e-6) {
            return listOf(origin, destination)
        }

        // Subtle perpendicular street jog so even pure E-W or N-S routes follow realistic urban blocks
        val perpScale = 0.14
        val perpLat = -dLng * perpScale
        val perpLng = dLat * perpScale

        val rawTurns = listOf(
            origin,
            RouteCoordinate(
                latitude = origin.latitude + dLat * 0.22 + perpLat * 0.15,
                longitude = origin.longitude + dLng * 0.04 + perpLng * 0.15
            ),
            RouteCoordinate(
                latitude = origin.latitude + dLat * 0.26 + perpLat * 0.85,
                longitude = origin.longitude + dLng * 0.36 + perpLng * 0.85
            ),
            RouteCoordinate(
                latitude = origin.latitude + dLat * 0.58 + perpLat * 0.60,
                longitude = origin.longitude + dLng * 0.42 + perpLng * 0.60
            ),
            RouteCoordinate(
                latitude = origin.latitude + dLat * 0.64 - perpLat * 0.35,
                longitude = origin.longitude + dLng * 0.78 - perpLng * 0.35
            ),
            RouteCoordinate(
                latitude = origin.latitude + dLat * 0.90 - perpLat * 0.18,
                longitude = origin.longitude + dLng * 0.84 - perpLng * 0.18
            ),
            destination
        )

        return smoothCorners(rawTurns)
    }

    /**
     * Softens sharp 90-degree corners with Chaikin corner-cutting for smooth bike turns.
     */
    private fun smoothCorners(points: List<RouteCoordinate>): List<RouteCoordinate> {
        if (points.size < 3) return points
        val smoothed = mutableListOf<RouteCoordinate>()
        smoothed.add(points.first())
        for (i in 1 until points.lastIndex) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]
            smoothed.add(lerp(prev, curr, 0.82))
            smoothed.add(lerp(curr, next, 0.18))
        }
        smoothed.add(points.last())
        return smoothed
    }

    /**
     * Computes the compass bearing in degrees [0, 360) from [from] to [to].
     * 0° = North, 90° = East, 180° = South, 270° = West.
     */
    fun computeBearingDegrees(from: RouteCoordinate, to: RouteCoordinate): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        if (kotlin.math.abs(lat2 - lat1) < 1e-9 && kotlin.math.abs(dLng) < 1e-9) {
            return 0f
        }
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val degrees = Math.toDegrees(atan2(y, x))
        return (((degrees % 360.0) + 360.0) % 360.0).toFloat()
    }

    /**
     * Approximates total route distance in kilometers using the Haversine formula.
     */
    fun totalDistanceKm(points: List<RouteCoordinate>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.lastIndex) {
            total += haversineKm(points[i], points[i + 1])
        }
        return total
    }

    /**
     * Samples the rider position, compass bearing, completed polyline, and remaining polyline
     * at normalized [progressFraction] in [0f, 1f] along [points].
     */
    fun sampleAlongRoute(
        points: List<RouteCoordinate>,
        progressFraction: Float
    ): RouteProgressSample {
        if (points.isEmpty()) {
            val zero = RouteCoordinate(0.0, 0.0)
            return RouteProgressSample(zero, 0f, emptyList(), emptyList())
        }
        if (points.size == 1) {
            val single = points.first()
            return RouteProgressSample(single, 0f, listOf(single), listOf(single))
        }

        val clamped = progressFraction.coerceIn(0f, 1f)
        val segmentLengths = DoubleArray(points.lastIndex) { idx ->
            haversineKm(points[idx], points[idx + 1]).coerceAtLeast(1e-6)
        }
        val totalLen = segmentLengths.sum().coerceAtLeast(1e-6)
        val targetDist = totalLen * clamped

        if (clamped <= 0f) {
            val heading = computeBearingDegrees(points[0], points[1])
            return RouteProgressSample(
                position = points.first(),
                bearingDegrees = heading,
                completedPoints = listOf(points.first()),
                remainingPoints = points
            )
        }
        if (clamped >= 1f) {
            val heading = computeBearingDegrees(points[points.lastIndex - 1], points.last())
            return RouteProgressSample(
                position = points.last(),
                bearingDegrees = heading,
                completedPoints = points,
                remainingPoints = listOf(points.last())
            )
        }

        var accumulated = 0.0
        val completed = mutableListOf(points.first())
        for (i in segmentLengths.indices) {
            val segLen = segmentLengths[i]
            val segEnd = accumulated + segLen
            if (targetDist <= segEnd || i == segmentLengths.lastIndex) {
                val localT = ((targetDist - accumulated) / segLen).coerceIn(0.0, 1.0)
                val pos = lerp(points[i], points[i + 1], localT)
                val bearing = computeBearingDegrees(points[i], points[i + 1])
                completed.add(pos)
                val remaining = buildList {
                    add(pos)
                    for (j in (i + 1) until points.size) {
                        add(points[j])
                    }
                }
                return RouteProgressSample(
                    position = pos,
                    bearingDegrees = bearing,
                    completedPoints = completed,
                    remainingPoints = remaining
                )
            }
            accumulated = segEnd
            completed.add(points[i + 1])
        }

        val lastBearing = computeBearingDegrees(points[points.lastIndex - 1], points.last())
        return RouteProgressSample(points.last(), lastBearing, points, listOf(points.last()))
    }

    /** Finds the point at fraction [t] between two route coordinates. */
    private fun lerp(a: RouteCoordinate, b: RouteCoordinate, t: Double): RouteCoordinate =
        RouteCoordinate(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t
        )

    /** Measures approximate earth-surface distance between two coordinates in kilometers. */
    private fun haversineKm(a: RouteCoordinate, b: RouteCoordinate): Double {
        val earthRadiusKm = 6371.0
        val dLat = (b.latitude - a.latitude) * PI / 180.0
        val dLng = (b.longitude - a.longitude) * PI / 180.0
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val sinLat = sin(dLat / 2.0)
        val sinLng = sin(dLng / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLng * sinLng
        return 2.0 * earthRadiusKm * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }
}
