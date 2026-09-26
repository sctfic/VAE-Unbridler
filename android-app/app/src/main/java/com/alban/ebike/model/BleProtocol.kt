package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BleProtocol {
    val serviceUuid: UUID = UUID.fromString("a6f50000-9b8a-4d9f-a0da-9e9d873d1101")
    val telemetryUuid: UUID = UUID.fromString("a6f50001-9b8a-4d9f-a0da-9e9d873d1101")
    val configUuid: UUID = UUID.fromString("a6f50002-9b8a-4d9f-a0da-9e9d873d1101")
    const val protocolVersion = 1
    const val defaultCircumferenceMm = 2360
    const val defaultThresholdCentiKmh = 2220

    fun decodeTelemetry(bytes: ByteArray): BikeTelemetry? {
        if (bytes.size != 32) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        if (version != protocolVersion) return null
        val flags = buffer.get().toInt() and 0xff
        return BikeTelemetry(
            wheelMoving = flags and 0x01 != 0,
            simulatedOutput = flags and 0x02 != 0,
            speedMode = flags and 0x04 != 0,
            modeSupported = flags and 0x08 != 0,
            sequence = buffer.short.toInt() and 0xffff,
            uptimeMs = buffer.int.toLong() and 0xffff_ffffL,
            wheelIntervalUs = buffer.int.toLong() and 0xffff_ffffL,
            motorIntervalUs = buffer.int.toLong() and 0xffff_ffffL,
            wheelSpeedKmh = (buffer.short.toInt() and 0xffff) / 100f,
            motorSpeedKmh = (buffer.short.toInt() and 0xffff) / 100f,
            wheelRevolutions = buffer.int.toLong() and 0xffff_ffffL,
            emittedPulses = buffer.int.toLong() and 0xffff_ffffL,
            circumferenceMm = buffer.short.toInt() and 0xffff,
        )
    }

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
    val speedMode: Boolean = false,
    val modeSupported: Boolean = false,
    val wheelMoving: Boolean = false,
    val simulatedOutput: Boolean = false,
    val sequence: Int = 0,
    val uptimeMs: Long = 0,
    val wheelIntervalUs: Long = 0,
    val motorIntervalUs: Long = 0,
    val wheelSpeedKmh: Float = 0f,
    val motorSpeedKmh: Float = 0f,
    val wheelRevolutions: Long = 0,
    val emittedPulses: Long = 0,
    val circumferenceMm: Int = BleProtocol.defaultCircumferenceMm,
)
