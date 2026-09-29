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
        val all = profile.update(1005.0, 110f)
        assertEquals(0.0, all.first().distanceM, 0.0)
        val points = DistanceAltitudeProfile.visible(all, 1005.0, 500.0)
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

    @Test fun missingAltitudePreservesHistoryButBreaksSlope() {
        val profile = DistanceAltitudeProfile()
        assertTrue(profile.update(0.0, null).isEmpty())
        profile.update(0.0, 100f)
        profile.update(10.0, 101f)
        val points = profile.update(1000.0, null)
        assertEquals(2, points.size)
        assertNull(profile.grade())
        assertTrue(profile.update(1010.0, 150f).last().segmentStart)
        assertNull(profile.grade())
    }

    @Test fun gradeMatchesTerminalTwentyMetersAndRemainsFixedAtRest() {
        val profile = DistanceAltitudeProfile()
        for (i in 0..10) profile.update(i * 5.0, 100f + i * .5f)
        assertEquals(10f, profile.grade()!!, .001f)
        repeat(1000) { profile.update(50.0, 200f + it); assertEquals(10f, profile.grade()!!, .001f) }
    }

    @Test fun slopeInterpolatesReferenceAndIsNotClampedAtTwentyFivePercent() {
        val profile = DistanceAltitudeProfile()
        profile.update(0.0, 100f); profile.update(15.0, 106f); profile.update(27.0, 110.8f)
        assertEquals(40f, profile.grade()!!, .001f)
        profile.update(27.0, 250f, segmentStart = true)
        assertNull(profile.grade())
        profile.update(35.0, 251f)
        assertNull(profile.grade())
        profile.clear()
        assertNull(profile.grade())
    }

    @Test fun allProfileModesKeepHistoryAndHaveCorrectScale() {
        val profile = DistanceAltitudeProfile()
        for (i in 0..3000 step 10) profile.update(i.toDouble(), 100f)
        val all = profile.update(3000.0, 100f)
        assertEquals(0.0, DistanceAltitudeProfile.visible(all, 3000.0, 3000.0).first().distanceM, 0.0)
        assertTrue(DistanceAltitudeProfile.visible(all, 3000.0, 2000.0).first().distanceM <= 1000.0)
        assertEquals(0f, DistanceAltitudeProfile.horizontalFraction(1000.0, 3000.0, 2000.0), 0f)
    }
}
