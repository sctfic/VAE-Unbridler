package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BleProtocolTest {
    @Test fun sensorDiagnosticsKeepsRawBounceIntervalsAndUnsignedCounters() {
        val packet = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1.toByte()); put(1.toByte()); putShort(65535.toShort())
            putInt(523); putInt(-1); putInt(500); putInt(0x80000000.toInt())
        }.array()
        val sensor = BleProtocol.decodeSensorDiagnostics(packet)!!
        assertTrue(sensor.intervalValid)
        assertFalse(sensor.limit99)
        assertFalse(sensor.acceleration15)
        assertFalse(sensor.holdPrevious)
        assertEquals(65535, sensor.sequence)
        assertEquals(523L, sensor.rawIntervalUs)
        assertEquals(4294967295L, sensor.edges)
        assertEquals(500L, sensor.rejectedEdges)
        assertEquals(2147483648L, sensor.droppedEdges)
        assertNull(BleProtocol.decodeTelemetry(packet))
        packet[1] = 0
        assertFalse(BleProtocol.decodeSensorDiagnostics(packet)!!.intervalValid)
        packet[1] = 15
        val filtered = BleProtocol.decodeSensorDiagnostics(packet)!!
        assertTrue(filtered.limit99)
        assertTrue(filtered.acceleration15)
        assertTrue(filtered.holdPrevious)
    }

    @Test fun rejectsTruncatedOrUnsupportedSensorDiagnostics() {
        assertNull(BleProtocol.decodeSensorDiagnostics(ByteArray(19)))
        assertNull(BleProtocol.decodeSensorDiagnostics(ByteArray(21)))
        assertNull(BleProtocol.decodeSensorDiagnostics(ByteArray(20)))
        val packet = ByteArray(20)
        packet[0] = 2
        assertNull(BleProtocol.decodeSensorDiagnostics(packet))
    }

    @Test fun decodesWheelAndMotorIndependentlyAtBenchSpeeds() {
        for ((speed, interval) in listOf(1900 to 447157, 3000 to 283200)) {
            val packet = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(1.toByte()); put(9.toByte()); putShort(65535.toShort())
                putInt(-1); putInt(interval); putInt(interval)
                putShort(speed.toShort()); putShort(speed.toShort())
                putInt(0x80000000.toInt()); putInt(-1)
                putShort(2360.toShort()); putShort(0.toShort())
            }.array()
            val telemetry = BleProtocol.decodeTelemetry(packet)!!
            assertEquals(speed / 100f, telemetry.wheelSpeedKmh, 0f)
            assertEquals(speed / 100f, telemetry.motorSpeedKmh, 0f)
            assertEquals(interval.toLong(), telemetry.wheelIntervalUs)
            assertEquals(interval.toLong(), telemetry.motorIntervalUs)
            assertEquals(65535, telemetry.sequence)
            assertEquals(4294967295L, telemetry.uptimeMs)
            assertEquals(2147483648L, telemetry.wheelRevolutions)
            assertEquals(4294967295L, telemetry.emittedPulses)
            assertEquals(2360, telemetry.circumferenceMm)
            assertTrue(telemetry.wheelMoving)
            assertFalse(telemetry.simulatedOutput)
        }
    }

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
