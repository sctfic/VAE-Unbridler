package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test

class BleProtocolTest {
    @Test fun configDistinguishesPreserveStandardAndSpeed() {
        assertEquals(0, BleProtocol.encodeConfig(2360)[1].toInt())
        assertEquals(2, BleProtocol.encodeConfig(2360, false)[1].toInt())
        assertEquals(3, BleProtocol.encodeConfig(2360, true)[1].toInt())
        assertEquals(8, BleProtocol.encodeConfig(2360, true).size)
    }

    @Test fun enabledModeDoesNotMeanCurrentlySimulating() {
        val bytes = ByteArray(32)
        bytes[0] = 1
        bytes[1] = 12
        val telemetry = BleProtocol.decodeTelemetry(bytes)!!
        assertTrue(telemetry.modeSupported)
        assertTrue(telemetry.speedMode)
        assertFalse(telemetry.simulatedOutput)
        bytes[1] = 2
        assertFalse(BleProtocol.decodeTelemetry(bytes)!!.modeSupported)
    }
}
