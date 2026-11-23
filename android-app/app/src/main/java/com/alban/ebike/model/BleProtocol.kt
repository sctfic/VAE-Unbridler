package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BleProtocol {
    val serviceUuid: UUID = UUID.fromString("a6f50000-9b8a-4d9f-a0da-9e9d873d1101")
    val eventsUuid: UUID = UUID.fromString("a6f50007-9b8a-4d9f-a0da-9e9d873d1101")
    val configUuid: UUID = UUID.fromString("a6f50002-9b8a-4d9f-a0da-9e9d873d1101")
    const val protocolVersion = 1
    const val defaultCircumferenceMm = 2360
    const val defaultThresholdCentiKmh = 2220


    fun encodeConfig(circumferenceMm: Int, speedMode: Boolean? = null): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(protocolVersion.toByte())
            put((if (speedMode == null) 0 else if (speedMode) 3 else 2).toByte())
            putShort(circumferenceMm.toShort())
            putShort(defaultThresholdCentiKmh.toShort())
            putShort(0)
        }.array()
}

data class BikeTelemetry(
    val eventType: Int = 0,
    val session: Long = 0,
    val eventTimeUs: Long = 0,
    val wheelTimeUs: Long = 0,
    val motorTimeUs: Long = 0,
    val motorTimeValid: Boolean = false,
    val confirmation: Int = 0,
    val mappedEventMs: Long = -1,
    val mappedWheelMs: Long = -1,
    val mappedMotorMs: Long = -1,
    val receivedMs: Long = -1,
    val speedMode: Boolean = false,
    val wheelMoving: Boolean = false,
    val simulatedOutput: Boolean = false,
    val sequence: Int = 0,
    val uptimeMs: Long = 0,
    val wheelIntervalUs: Long = 0,
    val motorIntervalUs: Long = 0,
    val wheelSpeedKmh: Float = 0f,
    val motorSpeedKmh: Float = 0f,
    val wheelRevolutions: Long = 0,
    val circumferenceMm: Int = BleProtocol.defaultCircumferenceMm,
)
