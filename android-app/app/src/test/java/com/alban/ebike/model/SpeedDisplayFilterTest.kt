package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test

class SpeedDisplayFilterTest {
    @Test fun weightsDurationRatherThanNotificationCount() {
        val sparse = SpeedDisplayFilter(Float.POSITIVE_INFINITY)
        val dense = SpeedDisplayFilter(Float.POSITIVE_INFINITY)
        sparse.accept(10f, 0); sparse.accept(20f, 1000)
        for (time in 0L..900L step 100) dense.accept(10f, time)
        dense.accept(20f, 1000)
        assertEquals(15f, sparse.value(2000)!!, .001f)
        assertEquals(sparse.value(2000)!!, dense.value(2000)!!, .001f)
        assertEquals(20f, sparse.value(3000)!!, .001f)
    }
    @Test fun rejectsSpikeWithoutPoisoningAverageAndExpiresHold() {
        val filter = SpeedDisplayFilter()
        filter.accept(20f, 1000)
        assertFalse(filter.accept(181.44f, 1200))
        assertFalse(filter.accept(74.94f, 1400))
        assertEquals(20f, filter.value(2000)!!, 0f)
        assertNull(filter.value(3001))
        assertFalse(filter.accept(Float.NaN, 3100))
        assertFalse(filter.accept(-1f, 3200))
    }
    @Test fun brakingIsAcceptedAndStopIsImmediate() {
        val filter = SpeedDisplayFilter()
        filter.accept(50f, 1000)
        assertTrue(filter.accept(5f, 1100))
        assertTrue(filter.accept(0f, 1200))
        assertEquals(0f, filter.value(1200)!!, 0f)
        assertFalse(filter.accept(20f, 1400))
    }
    @Test fun wheelWinsOverGpsZeroAndDisconnectResetsItsWindow() {
        val model = SpeedDisplayModel()
        val state = RideUiState(gpsSpeedValid = true, gpsSpeedKmh = 0f, gpsSpeedTimeMs = 1000,
            bluetoothReady = true, wheelSpeedKmh = 20f, motorSpeedKmh = 20f, wheelTelemetryTimeMs = 1000)
        val display = model.update(state, 1000)
        assertEquals("ROUE", display.source)
        assertEquals(20f, display.main!!, 0f)
        val disconnected = model.update(state.copy(bluetoothReady = false), 1100)
        assertEquals("GPS", disconnected.source)
        assertEquals(0f, disconnected.main!!, 0f)
        assertNull(disconnected.wheel)
        assertNull(model.update(state.copy(bluetoothReady = false), 4001).main)
    }
    @Test fun validatesTelemetryPeriodsAndDoesNotShowBogusMotorPairedWithBadWheel() {
        val standard = BikeTelemetry(wheelSpeedKmh = 30f, motorSpeedKmh = 30f,
            wheelIntervalUs = 283200, motorIntervalUs = 283200)
        assertTrue(TelemetryDisplayValidation.wheel(standard))
        assertTrue(TelemetryDisplayValidation.motor(standard))
        assertFalse(TelemetryDisplayValidation.wheel(standard.copy(wheelIntervalUs = 46000)))
        val spike = standard.copy(wheelSpeedKmh = 181.44f, motorSpeedKmh = 38.14f,
            wheelIntervalUs = 46824, motorIntervalUs = 222758, simulatedOutput = true)
        assertFalse(TelemetryDisplayValidation.wheel(spike))
        assertFalse(TelemetryDisplayValidation.motor(spike))
        assertTrue(TelemetryDisplayValidation.motor(standard.copy(simulatedOutput = true,
            motorSpeedKmh = 23f, motorIntervalUs = 369391)))
    }
}
