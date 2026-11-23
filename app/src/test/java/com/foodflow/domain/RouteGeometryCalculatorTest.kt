package com.foodflow.domain

import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.route.RouteGeometryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryCalculatorTest {

    @Test
    fun `decodeEncodedPolyline decodes Google reference polyline accurately`() {
        // Official Google Encoded Polyline Algorithm reference example:
        // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val decoded = RouteGeometryCalculator.decodeEncodedPolyline(encoded)

        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].latitude, 0.001)
        assertEquals(-120.2, decoded[0].longitude, 0.001)
        assertEquals(40.7, decoded[1].latitude, 0.001)
        assertEquals(-120.95, decoded[1].longitude, 0.001)
        assertEquals(43.252, decoded[2].latitude, 0.001)
        assertEquals(-126.453, decoded[2].longitude, 0.001)
    }

    @Test
    fun `buildUrbanRoadFallback creates multi-point road route anchored at origin and destination`() {
        val origin = RouteCoordinate(23.0225, 72.5714)
        val destination = RouteCoordinate(23.0469, 72.5310)
        val points = RouteGeometryCalculator.buildUrbanRoadFallback(origin, destination)

        assertTrue("Expected smooth multi-segment road polyline", points.size >= 12)
        assertEquals(origin.latitude, points.first().latitude, 0.0001)
        assertEquals(origin.longitude, points.first().longitude, 0.0001)
        assertEquals(destination.latitude, points.last().latitude, 0.0001)
        assertEquals(destination.longitude, points.last().longitude, 0.0001)
        assertTrue(RouteGeometryCalculator.totalDistanceKm(points) > 0.0)
    }

    @Test
    fun `sampleAlongRoute returns start destination and accurate direction-wise bike bearing`() {
        val origin = RouteCoordinate(23.0200, 72.5700)
        val corner = RouteCoordinate(23.0200, 72.5800) // Eastward
        val destination = RouteCoordinate(23.0300, 72.5800) // Northward
        val points = listOf(origin, corner, destination)

        val startSample = RouteGeometryCalculator.sampleAlongRoute(points, 0f)
        assertEquals(origin.latitude, startSample.position.latitude, 0.0001)
        assertEquals(origin.longitude, startSample.position.longitude, 0.0001)
        assertEquals(90f, startSample.bearingDegrees, 3f) // Facing East initially

        val endSample = RouteGeometryCalculator.sampleAlongRoute(points, 1f)
        assertEquals(destination.latitude, endSample.position.latitude, 0.0001)
        assertEquals(destination.longitude, endSample.position.longitude, 0.0001)
        assertTrue(endSample.completedPoints.size >= 2)
        assertEquals(0f, endSample.bearingDegrees, 3f) // Facing North on final leg
    }

    @Test
    fun `computeBearingDegrees computes accurate cardinal compass headings`() {
        val center = RouteCoordinate(23.0, 72.5)
        val north = RouteCoordinate(23.01, 72.5)
        val east = RouteCoordinate(23.0, 72.51)
        val south = RouteCoordinate(22.99, 72.5)
        val west = RouteCoordinate(23.0, 72.49)

        assertEquals(0f, RouteGeometryCalculator.computeBearingDegrees(center, north), 1.5f)
        assertEquals(90f, RouteGeometryCalculator.computeBearingDegrees(center, east), 1.5f)
        assertEquals(180f, RouteGeometryCalculator.computeBearingDegrees(center, south), 1.5f)
        assertEquals(270f, RouteGeometryCalculator.computeBearingDegrees(center, west), 1.5f)
    }
}
