package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test

class RideReadoutTest {
    private val live = RideUiState(gpsSpeedKmh = 30f, gpsSpeedValid = true,
        bluetoothReady = true, wheelSpeedKmh = 31f, altitudeM = 200f,
        inclinePercent = 9f, inclineValid = true, distanceM = 5000.0,
        elevationGainM = 300f, movingTimeMs = 900000)

    @Test fun selectedSnapshotSuppliesAllSixFieldsAndRetainsGlitch() {
        val point = TrackPoint(0.0, 0.0, 120f, 1000, distanceM = 1500.0,
            speedKmh = 18f, gradePercent = -2f, movingTimeMs = 120000,
            elevationGainM = 45f, speedApproximate = true, speedSource = "GPS")
        val shown = RideReadout.from(live, point)
        assertTrue(shown.historical)
        assertEquals(18f, shown.speedKmh!!, 0f)
        assertEquals(120f, shown.altitudeM!!, 0f)
        assertEquals(-2f, shown.gradePercent!!, 0f)
        assertEquals(1500.0, shown.distanceM, 0.0)
        assertEquals(45f, shown.elevationGainM!!, 0f)
        assertEquals(120000L, shown.movingTimeMs!!)
        assertTrue(shown.speedApproximate)
        assertEquals(shown, RideReadout.from(live.copy(gpsSpeedKmh = 40f,
            elevationGainM = 600f, movingTimeMs = 950000), point))
        assertEquals(5000.0, live.distanceM, 0.0)
    }

    @Test fun missingHistoricalValuesNeverBorrowLiveOrWheelMeasurements() {
        val point = TrackPoint(0.0, 0.0, 0f, 1000, altitudeValid = false)
        val shown = RideReadout.from(live, point)
        assertNull(shown.speedKmh); assertNull(shown.altitudeM); assertNull(shown.gradePercent)
        assertNull(shown.movingTimeMs); assertNull(shown.elevationGainM)
    }

    @Test fun returningToLiveRestoresCurrentValuesAndWheelFallback() {
        val shown = RideReadout.from(live.copy(gpsSpeedValid = false, gpsSpeedApproximate = true))
        assertFalse(shown.historical)
        assertEquals(31f, shown.speedKmh!!, 0f); assertEquals("ROUE", shown.speedSource)
        assertFalse(shown.speedApproximate)
        assertEquals(200f, shown.altitudeM!!, 0f); assertEquals(9f, shown.gradePercent!!, 0f)
        assertEquals(5000.0, shown.distanceM, 0.0)
        assertEquals(300f, shown.elevationGainM!!, 0f); assertEquals(900000L, shown.movingTimeMs!!)
    }
    @Test fun durationHidesOnlyZeroHours() {
        assertEquals("00:00", formatRideDuration(0))
        assertEquals("01:05", formatRideDuration(65000))
        assertEquals("59:59", formatRideDuration(3599000))
        assertEquals("01:00:00", formatRideDuration(3600000))
    }
    @Test fun historicalRestDoesNotBorrowCurrentRest() {
        val point = TrackPoint(0.0, 0.0, 100f, 1000, restTimeMs = 45000, resting = true)
        val shown = RideReadout.from(live.copy(restTimeMs = 90000, resting = false), point)
        assertTrue(shown.resting); assertEquals(45000L, shown.restTimeMs!!)
    }

    @Test fun altitudeUncertaintyFollowsTheSelectedPointAndItsSource() {
        val current = live.copy(altitudeSource = "GPS", altitudeUncertaintyM = 2.5f)
        assertEquals(2.5f, RideReadout.from(current).altitudeUncertaintyM!!, 0f)
        val point = TrackPoint(0.0, 0.0, 180f, 1000,
            altitudeSource = "IGN", altitudeUncertaintyM = .4f)
        val shown = RideReadout.from(current, point)
        assertEquals("IGN", shown.altitudeSource)
        assertEquals(.4f, shown.altitudeUncertaintyM!!, 0f)
        assertNull(RideReadout.from(current, point.copy(altitudeUncertaintyM = null)).altitudeUncertaintyM)
    }

}
