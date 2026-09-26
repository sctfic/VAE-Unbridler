package com.alban.ebike.data

import org.junit.Assert.*
import org.junit.Test

class DistanceAltitudeProfileTest {
    @Test fun stationaryAltitudeNoiseDoesNotChangeProfile() {
        val profile = DistanceAltitudeProfile()
        val initial = profile.update(0.0, 100f)
        repeat(1000) { assertEquals(initial, profile.update(0.0, 120f)) }
    }

    @Test fun windowKeepsOnePointForLeftEdgeInterpolation() {
        val profile = DistanceAltitudeProfile()
        for (distance in 0..1000 step 10) profile.update(distance.toDouble(), 100f)
        val points = profile.update(1005.0, 110f)
        assertEquals(500.0, points.first().distanceM, 0.0)
        assertEquals(1005.0, points.last().distanceM, 0.0)
    }

    @Test fun horizontalScaleIsFixedAndProportionalToTravel() {
        val initial = DistanceAltitudeProfile.horizontalFraction(100.0, 100.0)
        val afterTen = DistanceAltitudeProfile.horizontalFraction(100.0, 110.0)
        val afterTwenty = DistanceAltitudeProfile.horizontalFraction(100.0, 120.0)
        assertEquals(1f, initial, 0f)
        assertEquals(2 * (initial - afterTen), initial - afterTwenty, 0.00001f)
        assertEquals(0f, DistanceAltitudeProfile.horizontalFraction(100.0, 600.0), 0f)
    }

    @Test fun missingAltitudeDoesNotInventPointsAndStillPrunesOldData() {
        val profile = DistanceAltitudeProfile()
        assertTrue(profile.update(0.0, null).isEmpty())
        profile.update(0.0, 100f)
        profile.update(10.0, 101f)
        val points = profile.update(1000.0, null)
        assertEquals(1, points.size)
        assertEquals(10.0, points.single().distanceM, 0.0)
    }
}
