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
}
