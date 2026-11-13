package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test

class SpeedFallbackTest {
    @Test fun wheelOverridesValidGpsZero() {
        val state = RideUiState(gpsSpeedValid = true, gpsSpeedKmh = 0f,
            bluetoothReady = true, wheelSpeedKmh = 20f)
        assertEquals(20f, state.displayedSpeedKmh!!, 0f)
        assertEquals("ROUE", state.displayedSpeedSource)
    }
    @Test fun fallsBackToConnectedWheelOnly() {
        val state = RideUiState(gpsSpeedKmh = 14f, bluetoothReady = true, wheelSpeedKmh = 23f)
        assertEquals(23f, state.displayedSpeedKmh!!, 0f)
        assertEquals("ROUE", state.displayedSpeedSource)
        assertNull(state.copy(bluetoothReady = false).displayedSpeedKmh)
    }
}
