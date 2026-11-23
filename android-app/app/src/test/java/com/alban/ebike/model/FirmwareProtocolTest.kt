package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FirmwareProtocolTest {
    @Test fun decodesFirmwareAndBootConfirmation() {
        val bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1); put(0); put(0); put(7)
            putInt(0); putInt(0); putInt(12)
            put(1); put(0); putShort(FirmwareProtocol.hardwareRevision.toShort()); putInt(0x1e0000)
            put("0.4.12".toByteArray())
        }.array()
        val info = FirmwareProtocol.decode(bytes)!!
        assertTrue(info.stationary); assertTrue(info.bootConfirmed); assertTrue(info.supported)
        assertEquals(FirmwareProtocol.hardwareRevision, info.hardwareRevision)
        assertEquals(1, info.slot); assertEquals(0, info.targetSlot)
        assertEquals(12, info.versionCode); assertEquals("0.4.12", info.version)
        bytes[3] = 1
        assertFalse(FirmwareProtocol.decode(bytes)!!.stationary)
        assertFalse(FirmwareProtocol.decode(bytes)!!.bootConfirmed)
        assertNull(FirmwareProtocol.decode(bytes.copyOf(47)))
        bytes[0] = 2
        assertNull(FirmwareProtocol.decode(bytes))
    }

    @Test fun encodesExactBeginAndOffsetPackets() {
        val hash = (0 until 32).joinToString("") { "%02x".format(it) }
        val begin = FirmwareProtocol.begin(0x123456, hash, 258)
        assertEquals(41, begin.size)
        val b = ByteBuffer.wrap(begin).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(6, b.get().toInt()); assertEquals(0x123456, b.int)
        val sha = ByteArray(32); b.get(sha)
        assertArrayEquals(ByteArray(32) { it.toByte() }, sha)
        assertEquals(258, b.int)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12, 9, 8),
            FirmwareProtocol.chunk(0x12345678, byteArrayOf(9, 8, 7), 2))
    }

    @Test fun refusesInvalidHashAndOversizedChunk() {
        assertThrows(IllegalArgumentException::class.java) { FirmwareProtocol.begin(12, "not-a-hash", 2) }
        assertThrows(IllegalArgumentException::class.java) { FirmwareProtocol.chunk(0, ByteArray(241), 241) }
        assertThrows(IllegalArgumentException::class.java) { FirmwareProtocol.chunk(-1, byteArrayOf(1), 1) }
    }
    @Test fun decodesWakePreparationAndCompletion() {
        val b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1); put(5); put(0); put(15)
            putInt(0); putInt(0); putInt(4)
            put(0); put(1); putShort(FirmwareProtocol.hardwareRevision.toShort()); putInt(0x1e0000)
            put("1.0.5".toByteArray())
        }.array()
        val preparing = FirmwareProtocol.decode(b)!!
        assertFalse(preparing.wakeComplete)
        assertTrue(preparing.stationary); assertTrue(preparing.supported)
        b[3] = 31
        assertTrue(FirmwareProtocol.decode(b)!!.wakeComplete)
        b[1] = 0; b[3] = 7
        assertEquals(5, FirmwareProtocol.PREPARE_WAKE.toInt())
    }
}
