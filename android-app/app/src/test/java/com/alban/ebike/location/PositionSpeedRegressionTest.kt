package com.alban.ebike.location
import org.junit.Assert.*
import org.junit.Test
class PositionSpeedRegressionTest {
    private fun fix(t: Long, north: Double) = GpsMotionFilter.Fix(t, north / 6371000 * 180 / Math.PI, 0.0, 3f, null, null)
    @Test fun recoversSpeedWithoutNativeMeasurement() {
        val regression = PositionSpeedRegression()
        var speed: Float? = null
        for (i in 0..6) speed = regression.accept(fix(i * 1000L, i * 5.0), i * 1000L)
        assertEquals(18f, speed!!, .01f)
    }
    @Test fun insufficientHistoryAndStaleFixesAreUnavailable() {
        val regression = PositionSpeedRegression()
        assertNull(regression.accept(fix(0, 0.0), 0))
        assertNull(regression.accept(fix(1000, 5.0), 1000))
        assertNull(regression.accept(fix(2000, 10.0), 9000))
        assertNull(regression.accept(fix(10000, 50.0), 10000))
    }
    @Test fun stationaryJitterDoesNotProduceMotion() {
        val regression = PositionSpeedRegression()
        var speed: Float? = null
        for (i in 0..6) speed = regression.accept(fix(i * 1000L, if (i % 2 == 0) 1.0 else -1.0), i * 1000L)
        assertEquals(0f, speed!!, .01f)
    }
}
