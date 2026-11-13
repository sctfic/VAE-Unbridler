package com.alban.ebike.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WheelCalibrationTest {
    private fun sample(metres: Double, time: Long, ticks: Double = metres / 2.0, segment: Long = 0,
        accuracy: Float = 2f, north: Double = 0.0) = WheelCalibration.Sample(time,
        Math.toDegrees(north / 6371000), Math.toDegrees(metres / 6371000), accuracy, ticks, segment)
    private fun straight(length: Int, segment: Long = 0, startTime: Long = 0) = (0..length step 10).map {
        sample(it.toDouble(), startTime + it * 100L, segment = segment)
    }

    @Test fun usesCounterDifferenceWithoutSubtractingAnotherTick() {
        val result = WheelCalibration.analyse(straight(600))!!
        assertEquals(2000, result.circumferenceMm)
        assertEquals(300.0, result.intervals, .0001)
        assertEquals(600.0, result.distanceM, .001)
    }
    @Test fun enforces500MetreMinimum() {
        assertNull(WheelCalibration.analyse(straight(490)))
        assertNotNull(WheelCalibration.analyse(straight(500)))
    }
    @Test fun choosesLongestContinuousSegment() {
        val result = WheelCalibration.analyse(straight(500) + straight(700, 1, 60000))!!
        assertEquals(700.0, result.distanceM, .001)
    }
    @Test fun poorReceptionAndCounterResetCannotBridgeShortSegments() {
        val route = straight(800).mapIndexed { i, p -> if (i == 40) p.copy(accuracyM = 15f) else p }
        assertNull(WheelCalibration.analyse(route))
        val reset = straight(800).mapIndexed { i, p -> if (i >= 40) p.copy(ticks = p.ticks - 200) else p }
        assertNull(WheelCalibration.analyse(reset))
    }
    @Test fun curvesAreNotMistakenForA500MetreStraight() {
        val circle = (0..60).map { i ->
            val angle = i * Math.PI / 30
            sample(120 * cos(angle), i * 1000L, i * Math.PI * 2, north = 120 * sin(angle))
        }
        assertNull(WheelCalibration.analyse(circle))
    }
    @Test fun distanceFollowsPolylineRatherThanEndpointChord() {
        val path = (0..60).map { i -> sample(i * 10.0, i * 1000L, i * 5.0,
            north = if (i <= 30) i * .5 else (60 - i) * .5) }
        val result = WheelCalibration.analyse(path)!!
        assertTrue(result.distanceM > 600.0)
        assertTrue(result.straightness >= .95)
    }
    @Test fun thresholdIsStrictlyGreaterThanOnePercent() {
        assertFalse(WheelCalibrationProposal(2020, 303.0, 150.0, 1.0, 0).significant(2000))
        assertTrue(WheelCalibrationProposal(2021, 303.15, 150.0, 1.0, 0).significant(2000))
        assertTrue(WheelCalibrationProposal(1979, 296.85, 150.0, 1.0, 0).significant(2000))
    }
    @Test fun capturesGpsAndTicksOnSameTimelineWithoutUsingSpeed() {
        val calibration = WheelCalibration()
        for (i in 0..160) {
            val t = i * 500L
            calibration.telemetry(t + 100, t, i * 2L)
            if (i % 2 == 0) calibration.gps(t + 100, t + 100, 0.0, Math.toDegrees(i * 4.0 / 6371000), 2f, true)
        }
        assertEquals(2000, calibration.evaluate()!!.circumferenceMm)
        calibration.reset()
        assertNull(calibration.evaluate())
    }
    @Test fun disconnectPreventsJoiningTwoShortStretches() {
        val calibration = WheelCalibration()
        repeat(2) { run ->
            for (i in 0..30) {
                val t = run * 40000L + i * 1000L
                calibration.telemetry(t, t, (run * 200 + i * 5).toLong())
                calibration.gps(t, t, 0.0, Math.toDegrees((run * 200 + i * 10.0) / 6371000), 2f, true)
            }
            calibration.disconnect()
        }
        assertNull(calibration.evaluate())
    }
    @Test fun acceptsSevenMetresAccuracyButRejectsWorse() {
        assertNotNull(WheelCalibration.analyse(straight(600).map { it.copy(accuracyM = 7f) }))
        assertNull(WheelCalibration.analyse(straight(600).map { it.copy(accuracyM = 7.01f) }))
    }
    @Test fun acceptsGentleCurveBetween95And98PercentStraightness() {
        val path = (0..60).map { i -> sample(i * 10.0, i * 1000L, i * 5.0,
            north = if (i <= 30) i * 2.5 else (60 - i) * 2.5) }
        val result = WheelCalibration.analyse(path)!!
        assertTrue(result.distanceM > 600)
        assertTrue(result.straightness in .95.. .98)
    }
}
