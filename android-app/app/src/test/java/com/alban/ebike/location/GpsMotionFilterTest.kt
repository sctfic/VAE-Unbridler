package com.alban.ebike.location

import org.junit.Assert.*
import org.junit.Test

class GpsMotionFilterTest {
    @Test fun reportsMissingSpeedUncertainty() {
        val result = GpsMotionFilter().accept(fix(1000, speed = 4f, error = null), 1000)
        assertFalse(result.reliable)
        assertEquals("incertitude vitesse absente", result.reason)
    }
    private fun fix(t: Long, northM: Double = 0.0, speed: Float? = 0f,
        error: Float? = 0.2f, accuracy: Float = 3f) =
        GpsMotionFilter.Fix(t, 48.0 + northM / 111195.0, 2.0, accuracy, speed, error)

    @Test fun stationaryJitterDoesNotBecomeSpeed() {
        val filter = GpsMotionFilter()
        repeat(20) { i ->
            val result = filter.accept(fix(i * 1000L, if (i % 2 == 0) 2.0 else -2.0), i * 1000L)
            assertEquals(0f, result.speedKmh, 0f)
            assertFalse(result.moving)
        }
    }

    @Test fun missingSpeedDoesNotFallBackToPositionDifferences() {
        val filter = GpsMotionFilter()
        repeat(6) { i ->
            val result = filter.accept(fix(i * 1000L, i * 4.06, speed = null), i * 1000L)
            assertFalse(result.reliable)
            assertEquals(0f, result.speedKmh, 0f)
        }
    }

    @Test fun falseReportedSpeedNeedsPositionConfirmation() {
        val filter = GpsMotionFilter()
        repeat(10) { i ->
            val result = filter.accept(fix(i * 1000L, i % 2 * 2.0, speed = 4.06f), i * 1000L)
            assertFalse(result.moving)
        }
    }

    @Test fun preciseNativeSpeedStartsBeforeFullAccuracyBaseline() {
        val filter = GpsMotionFilter()
        assertFalse(filter.accept(fix(0, speed = 4f, accuracy = 7f), 0).moving)
        val result = filter.accept(fix(1000, 4.0, speed = 4f, accuracy = 7f), 1000)
        assertTrue(result.moving)
        assertEquals(14.4f, result.speedKmh, .01f)
    }

    @Test fun cyclingThenStopping() {
        val filter = GpsMotionFilter()
        var result = filter.accept(fix(0, speed = 4f), 0)
        for (i in 1..4) result = filter.accept(fix(i * 1000L, i * 4.0, 4f), i * 1000L)
        assertTrue(result.moving)
        assertEquals(14.4f, result.speedKmh, 0.01f)
        result = filter.accept(fix(5000, 16.0, 0.1f), 5000)
        assertTrue(result.reliable)
        assertEquals(0f, result.speedKmh, 0f)
    }

    @Test fun rejectsOldOutOfOrderAndUncertainFixes() {
        val filter = GpsMotionFilter()
        assertFalse(filter.accept(fix(0, speed = 4f), 5000).reliable)
        assertFalse(filter.accept(fix(6000, speed = 4f, error = 3f), 6000).reliable)
        assertFalse(filter.accept(fix(5000), 6000).reliable)
        assertFalse(filter.accept(fix(7000, accuracy = 40f), 7000).reliable)
        assertFalse(filter.accept(fix(8000, speed = Float.NaN), 8000).reliable)
    }
}
