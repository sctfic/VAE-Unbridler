package com.alban.ebike.location

import org.junit.Assert.*
import org.junit.Test

class AltitudeQualityTest {
    @Test fun repeatedAltitudeDuringTravelBecomesSuspectAndRecoveryIsConfirmed() {
        val filter = AltitudeQuality()
        for (i in 0..19) filter.accept(i * 1000L, i * 5.0, 230.100006, 5f, false)
        assertFalse(filter.accept(20000, 100.0, 230.100006, 5f, false))
        assertFalse(filter.accept(21000, 105.0, 242.0, 5f, false))
        assertFalse(filter.accept(22000, 110.0, 242.1, 5f, false))
        assertFalse(filter.accept(23000, 115.0, 242.2, 5f, false))
        assertTrue(filter.accept(24000, 120.0, 242.3, 5f, false))
    }
    @Test fun flatStationaryAltitudeIsNotRejectedAsStale() {
        val filter = AltitudeQuality()
        for (i in 0..100) {
            val valid = filter.accept(i * 1000L, 0.0, 230.0, 5f, false)
            if (i >= 2) assertTrue(valid)
        }
    }
    @Test fun poorOrMissingVerticalAccuracyIsNotAnAltitudeMeasurement() {
        val filter = AltitudeQuality()
        assertFalse(filter.accept(0, 0.0, 230.0, null, false))
        assertFalse(filter.accept(1000, 0.0, 230.0, 30f, false))
        assertFalse(filter.accept(2000, 0.0, Double.NaN, 3f, false))
    }
}
