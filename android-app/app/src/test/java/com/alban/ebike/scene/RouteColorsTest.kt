package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class RouteColorsTest {
    private fun point(i: Int) = TrackPoint(48.0 + i * .0001, 2.0, 100f + i * 10,
        i * 1000L, i * 12.0, i == 0, i * 25f, i * 15f - 15)

    @Test fun eachVertexCarriesItsOwnMetricColorAndGapsStayOpen() {
        val points = (0..3).map { point(it).copy(segmentStart = it == 0 || it == 3) }
        for (metric in RouteMetric.entries) {
            val mesh = RideSceneMesh.build(points, null, false, 0.0, metric)
            assertEquals(12, mesh.route.size)
            assertEquals(mesh.route.size, mesh.routeColors.size)
            assertFalse(mesh.routeColors.take(3) == mesh.routeColors.drop(3).take(3))
        }
    }

    @Test fun restColorUsesPointStateEvenWhenDistanceDoesNotAdvance() {
        val points = listOf(point(0), point(1).copy(resting = true),
            point(2).copy(distanceM = 12.0, resting = true), point(3).copy(distanceM = 13.0))
        for (metric in RouteMetric.entries) {
            val colors = RideSceneMesh.build(points, null, false, 0.0, metric).routeColors
            assertArrayEquals(floatArrayOf(1f, 0f, 1f, 1f, 0f, 1f), colors.copyOfRange(6, 12), 0f)
            assertArrayEquals(floatArrayOf(1f, 0f, 1f, 1f, 0f, 1f), colors.copyOfRange(12, 18), 0f)
        }
    }

    @Test fun missingDataIsNeutralNotZeroAndValuesSaturateAtLegendLimits() {
        val p = point(0).copy(speedKmh = null, altitudeValid = false, gradePercent = null)
        for (metric in RouteMetric.entries) assertArrayEquals(floatArrayOf(.45f, .50f, .55f),
            RouteColors.color(p, metric, 0f to 50f), 0f)
        assertArrayEquals(RouteColors.palette(1f), RouteColors.color(point(3), RouteMetric.SPEED, 0f to 50f), 0f)
    }

    @Test fun cameraFitsEverySupportedTilt() {
        val points = listOf(WorldPoint(-500.0, -900.0, -300.0), WorldPoint(800.0, 600.0, 400.0))
        for (degrees in 20..80 step 5) for (yaw in 0..360 step 30) {
            val tilt = Math.toRadians(degrees.toDouble()); val heading = Math.toRadians(yaw.toDouble())
            val fit = TrackCamera.fit(points, heading, .5, tilt)
            for (point in points) {
                val p = TrackCamera.project(point, heading, fit, .5, tilt)
                assertTrue(kotlin.math.abs(p.x) <= .80001)
                assertTrue(kotlin.math.abs(p.y) <= .64001)
            }
        }
    }
}
