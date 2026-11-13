package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test

class PauseSegmentsTest {
    private fun point(time: Long, broken: Boolean = false) = TrackPoint(0.0, time / 100000.0, 10f, time,
        distanceM = time.toDouble(), segmentStart = broken)

    @Test fun followsIntermediatePointsAndStopsAtResume() {
        val start = point(10); val middle = point(20); val end = point(30)
        assertEquals(listOf(start to middle, middle to end),
            pauseSegments(listOf(RidePause(start, end)), listOf(point(0), middle, point(40)), point(50)))
    }

    @Test fun openPauseUsesCurrentPointWithoutBridgingGpsGap() {
        val start = point(10); val afterGap = point(20, true); val current = point(30)
        assertEquals(listOf(afterGap to current),
            pauseSegments(listOf(RidePause(start)), listOf(afterGap), current))
    }
    @Test fun splitsExactlyAtRestBoundaries() {
        val pause = RidePause(point(20), point(40))
        assertEquals(listOf(Triple(0f, .2f, false), Triple(.2f, .4f, true), Triple(.4f, 1f, false)),
            restSections(0.0, 100.0, listOf(pause)))
    }

    @Test fun ongoingRestExtendsToEndAndStationaryPauseDoesNotColourMovement() {
        assertEquals(listOf(Triple(0f, .5f, false), Triple(.5f, 1f, true)),
            restSections(0.0, 40.0, listOf(RidePause(point(20)))))
        assertTrue(restSections(0.0, 40.0, listOf(RidePause(point(20), point(20)))).none { it.third })
    }
}
