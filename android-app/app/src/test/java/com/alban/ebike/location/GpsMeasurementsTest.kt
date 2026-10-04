package com.alban.ebike.location

import org.junit.Assert.*
import org.junit.Test

class GpsMeasurementsTest {
    private fun fix(i: Int, north: Double = i * 1.15, speed: Float? = 1.15f, error: Float = .22f) =
        GpsMotionFilter.Fix(i * 1000L, 48.0 + north / 111195.0, 2.0, 5f, speed, error)

    @Test fun slowDepartureDoesNotAlternateBetweenZeroAndRegression() {
        val measurements = GpsMeasurements()
        for (i in 0..20) {
            val result = measurements.accept(fix(i, error = if (i % 2 == 0) .17f else .25f), i * 1000L)
            if (i >= 3) {
                assertTrue(result.motion.reliable)
                assertFalse(result.approximate)
                assertEquals(4.14f, result.motion.speedKmh, .1f)
                assertTrue(result.track.distanceM > 0)
            }
        }
    }
    @Test fun estimatedSpeedAndDistanceStartTogether() {
        val measurements = GpsMeasurements()
        for (i in 0..10) {
            val result = measurements.accept(fix(i, i * 5.0, null), i * 1000L)
            if (i >= 2) {
                assertTrue(result.approximate)
                assertTrue(result.motion.moving)
                assertTrue(result.track.distanceM > 0)
            }
        }
    }
    @Test fun jumpCannotEscapeThroughRegression() {
        val measurements = GpsMeasurements()
        for (i in 0..5) measurements.accept(fix(i), i * 1000L)
        val result = measurements.accept(fix(6, 500.0), 6000)
        assertFalse(result.track.accepted)
        assertFalse(result.motion.reliable)
        assertFalse(result.approximate)
    }
    @Test fun restartDoesNotCountTheWholeRestDrift() {
        val track = GpsTrackFilter()
        val stopped = GpsMotionFilter.Result(0f, true, false)
        for (i in 0..20) track.accept(fix(i, i.toDouble(), 0f), i * 1000L, stopped)
        val result = track.accept(fix(21, 21.0), 21000, GpsMotionFilter.Result(4.14f, true, true))
        assertEquals(1.0, result.distanceM, .01)
    }
    @Test fun stationaryJitterDoesNotStartActivity() {
        val measurements = GpsMeasurements()
        for (i in 0..20) {
            val result = measurements.accept(fix(i, (i % 2) * 2.0, .1f), i * 1000L)
            assertEquals(0f, result.motion.speedKmh, 0f)
            assertEquals(0.0, result.track.distanceM, 0.0)
        }
    }
}
