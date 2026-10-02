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
}
