package com.alban.ebike.data
import org.junit.Assert.*
import org.junit.Test
class RideMetricsTest {
    @Test fun timersOwnEveryIntervalExclusivelyIncludingSignalLossAndDelays() {
        val timer = MovingTimer()
        assertTrue(timer.resting)
        timer.update(0, null, 4f)
        timer.update(1000, 4f, 4f)
        assertEquals(1000L, timer.restMilliseconds)
        timer.update(2000, 5f, 4f)
        assertFalse(timer.resting)
        assertEquals(0L, timer.restMilliseconds)
        timer.update(3000, null, 4f)
        assertTrue(timer.resting)
        assertEquals(1000L, timer.milliseconds)
        timer.update(10000, Float.NaN, 4f)
        assertEquals(7000L, timer.restMilliseconds)
        timer.update(11000, 0f, 4f)
        assertEquals(8000L, timer.restMilliseconds)
        timer.update(12000, 6f, 4f)
        assertEquals(0L, timer.restMilliseconds)
        timer.update(13000, 6f, 4f)
        assertEquals(2000L, timer.milliseconds)
        timer.suspend()
        timer.update(90000, 6f, 4f)
        assertEquals(2000L, timer.milliseconds)
        timer.reset()
        assertTrue(timer.resting)
        assertEquals(0L, timer.milliseconds)
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

}
