package com.alban.ebike.data
import org.junit.Assert.*
import org.junit.Test
class RideMetricsTest {
    @Test fun timerRejectsStopsThresholdAndGaps() {
        val timer = MovingTimer()
        timer.update(0, 10f, 4f)
        assertEquals(1000L, timer.update(1000, 10f, 4f))
        assertEquals(1000L, timer.update(2000, 4f, 4f))
        timer.update(3000, 10f, 4f)
        assertEquals(1000L, timer.update(10000, 10f, 4f))
        assertEquals(1000L, timer.update(11000, null, 4f))
        timer.reset(); assertEquals(0L, timer.milliseconds)
    }
    @Test fun gainRejectsNoiseAndDoesNotBridgeMissingAltitude() {
        val gain = ElevationGain()
        gain.update(100f, true)
        repeat(20) { gain.update(101f, false); gain.update(100f, false) }
        assertEquals(0f, gain.metres, 0f)
        gain.update(104f, false); gain.update(108f, false)
        assertEquals(8f, gain.metres, 0f)
        gain.update(null, false); gain.update(200f, false)
        assertEquals(8f, gain.metres, 0f)
        gain.reset(); assertEquals(0f, gain.metres, 0f)
    }
    @Test fun restCountsOnlyMeasuredStopsAndResetsAtEveryValidMovement() {
        val timer = MovingTimer()
        timer.update(0, 0f, 4f); timer.update(1000, 4f, 4f)
        assertTrue(timer.resting); assertEquals(1000L, timer.restMilliseconds)
        timer.update(2000, null, 4f)
        assertFalse(timer.resting); assertEquals(1000L, timer.restMilliseconds)
        timer.update(3000, 0f, 4f); timer.update(4000, 0f, 4f)
        assertEquals(2000L, timer.restMilliseconds)
        timer.update(20000, 0f, 4f)
        assertEquals(2000L, timer.restMilliseconds)
        timer.update(21000, 5f, 4f)
        assertFalse(timer.resting); assertEquals(0L, timer.restMilliseconds)
        timer.update(22000, 0f, 4f); timer.update(23000, 0f, 4f)
        assertEquals(1000L, timer.restMilliseconds)
        timer.reset(); assertEquals(0L, timer.restMilliseconds); assertFalse(timer.resting)
    }

}
