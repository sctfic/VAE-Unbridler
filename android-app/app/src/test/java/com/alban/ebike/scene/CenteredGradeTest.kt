package com.alban.ebike.scene

import com.alban.ebike.data.GradeEstimate
import com.alban.ebike.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class CenteredGradeTest {
    @Test fun assignsToCentralHistoryPointAndKeepsRecentEndUnknown() {
        val track = VisibleTrack()
        for (i in 0..9) track.add(TrackPoint(0.0, i * .00002, 100f, i * 1000L,
            distanceM = i * 2.0, altitudeSource = "IGN"))
        val estimate = GradeEstimate(10f, 9.0, 0.0, 18.0)
        val corrected = track.applyCenteredGrade(estimate, "IGN")!!
        assertTrue(corrected.distanceM in 8.0..10.0)
        assertEquals(10f, corrected.gradePercent!!, 0f)
        assertNull(track.snapshot().last().gradePercent)
        assertNull(track.applyCenteredGrade(estimate, "IGN"))
        assertNull(track.applyCenteredGrade(estimate, "GPS"))
    }

    @Test fun neverAssignsToRestOrOutsideTheRegressionWindow() {
        val track = VisibleTrack()
        track.add(TrackPoint(0.0, 0.0, 100f, 0, distanceM = 0.0, altitudeSource = "GPS"))
        track.add(TrackPoint(0.0, 0.0, 100f, 1000, distanceM = 5.0, altitudeSource = "GPS", resting = true))
        assertNull(track.applyCenteredGrade(GradeEstimate(5f, 5.0, 4.0, 6.0), "GPS"))
    }
}
