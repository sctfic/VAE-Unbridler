package com.alban.ebike.location

import org.junit.Assert.*
import org.junit.Test

class GpsContinuityTest {
    private fun fix(i: Int, speed: Float? = 8f, error: Float? = .3f, north: Double = i * 8.0) =
        GpsMotionFilter.Fix(i * 1000L, 48 + north / 111195.0, 2.0, 4f, speed, error)

    @Test fun uncertaintyPeaksDoNotBreakMovingTrack() {
        val motion = GpsMotionFilter()
        val track = GpsTrackFilter()
        var distance = 0.0
        for (i in 0..20) {
            val f = fix(i, error = if (i in 7..10) 1.82f else .3f)
            val m = motion.accept(f, f.timeMs)
            val t = track.accept(f, f.timeMs, m)
            if (i >= 2) { assertTrue(m.reliable); assertTrue(t.append); assertFalse(t.segmentStart) }
            distance += t.distanceM
        }
        assertEquals(160.0, distance, 1.0)
    }

    @Test fun missingSpeedExpiresButPositionsContinue() {
        val motion = GpsMotionFilter()
        val track = GpsTrackFilter()
        for (i in 0..20) {
            val f = fix(i, speed = if (i >= 5) null else 8f)
            val m = motion.accept(f, f.timeMs)
            val t = track.accept(f, f.timeMs, m)
            if (i in 5..6) assertTrue(m.reliable)
            if (i >= 7) assertFalse(m.reliable)
            if (i >= 2) { assertTrue(t.append); assertFalse(t.segmentStart) }
        }
    }

    @Test fun medianRejectsSpikeAndStopIsImmediate() {
        val motion = GpsMotionFilter()
        for (i in 0..5) motion.accept(fix(i), i * 1000L)
        assertEquals(28.8f, motion.accept(fix(6, speed = 20f), 6000).speedKmh, .01f)
        assertEquals(0f, motion.accept(fix(7, speed = .1f), 7000).speedKmh, 0f)
    }

    @Test fun sustainedAccelerationSettlesWithinThreeSeconds() {
        val motion = GpsMotionFilter()
        for (i in 0..5) motion.accept(fix(i), i * 1000L)
        var result = motion.accept(fix(6, speed = 11f), 6000)
        for (i in 7..9) result = motion.accept(fix(i, speed = 11f), i * 1000L)
        assertEquals(39.6f, result.speedKmh, .5f)
    }

    @Test fun realGapAndJumpBreakTrackButDuplicateDoesNot() {
        val track = GpsTrackFilter()
        val moving = GpsMotionFilter.Result(28.8f, true, true)
        track.accept(fix(0), 0, moving)
        assertFalse(track.accept(fix(1), 1000, moving).segmentStart)
        assertFalse(track.accept(fix(1), 1000, moving).accepted)
        assertFalse(track.accept(fix(2), 2000, moving).segmentStart)
        assertTrue(track.accept(fix(8), 8000, moving).segmentStart)
        assertFalse(track.accept(fix(9, north = 1000.0), 9000, moving).accepted)
        assertTrue(track.accept(fix(10), 10000, moving).segmentStart)
    }

    @Test fun stationaryJitterAddsNoDistance() {
        val track = GpsTrackFilter()
        val stopped = GpsMotionFilter.Result(0f, true, false)
        for (i in 0..20) {
            val f = fix(i, speed = .2f, north = if (i % 2 == 0) 2.0 else -2.0)
            val t = track.accept(f, f.timeMs, stopped)
            assertEquals(0.0, t.distanceM, 0.0)
            if (i > 0) assertFalse(t.append)
        }
    }
}
