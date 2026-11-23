package com.alban.ebike.firmware

import com.alban.ebike.model.FirmwareInfo
import com.alban.ebike.model.FirmwareProtocol
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FirmwareTransferTest {
    private fun ack(received: Int, state: Int = 1) = FirmwareInfo(state, 0, 39, received, 10000,
        5, 0, 1, 0x1e0000, "1.0.6", FirmwareProtocol.hardwareRevision)
    private suspend fun image(size: Int, block: suspend (File, ByteArray) -> Unit) {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val file = File.createTempFile("ota-transfer", ".bin")
        try { file.writeBytes(bytes); block(file, bytes) } finally { file.delete() }
    }
    @Test fun fastWindowsAreConfirmedAsBlocksAndReconstructTheImage() = runBlocking {
        image(9001) { file, original ->
            val receiver = ByteArrayOutputStream(); var clock = 0L; var writes = 0; var reads = 0
            val result = FirmwareTransfer({ clock }, { clock += it }).send(file, original.size, 240,
                { packet ->
                    writes++; clock++
                    val offset = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int
                    assertEquals(receiver.size(), offset)
                    receiver.write(packet, 4, packet.size - 4)
                }, { reads++; ack(receiver.size()) }, { durable, _ -> assertEquals(receiver.size(), durable) })
            assertArrayEquals(original, receiver.toByteArray()); assertEquals(original.size, result.bytes)
            assertEquals(0, result.retries); assertEquals(3, reads); assertTrue(writes > reads * 8)
        }
    }
    @Test fun aMissingPacketRetransmitsFromTheCommittedOffset() = runBlocking {
        image(4001) { file, original ->
            val receiver = ByteArrayOutputStream(); var clock = 0L; var dropped = false
            val result = FirmwareTransfer({ clock }, { clock += it }).send(file, original.size, 240,
                { packet ->
                    val offset = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int
                    if (offset == 240 && !dropped) dropped = true
                    else if (offset == receiver.size()) receiver.write(packet, 4, packet.size - 4)
                }, { ack(receiver.size()) }, { _, _ -> })
            assertTrue(dropped); assertEquals(1, result.retries); assertArrayEquals(original, receiver.toByteArray())
        }
    }
    @Test fun queuedBytesAreNotReportedAsWrittenUntilTheReceiverConfirms() = runBlocking {
        image(500) { file, _ ->
            var clock = 0L; var polls = 0; var progress = 0
            FirmwareTransfer({ clock }, { clock += it }).send(file, 500, 240,
                { _ -> assertEquals(0, progress) }, { polls++; ack(if (polls < 4) 0 else 500) },
                { durable, _ -> progress = durable })
            assertEquals(500, progress); assertEquals(4, polls)
        }
    }
    @Test fun stalledReceiverFailsAfterBoundedRetries() = runBlocking {
        image(500) { file, _ ->
            var clock = 0L; var writes = 0
            val failure = runCatching {
                FirmwareTransfer({ clock }, { clock += it }).send(file, 500, 240,
                    { _ -> writes++ }, { ack(0) }, { _, _ -> fail("No durable progress") })
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException); assertEquals(12, writes); assertEquals(6000L, clock)
        }
    }
    @Test fun invalidOrFailedAcknowledgementsAbort() = runBlocking {
        for (reply in listOf(ack(501), ack(0, 4))) image(500) { file, _ ->
            val failure = runCatching {
                FirmwareTransfer({ 0L }).send(file, 500, 240, { _ -> }, { reply }, { _, _ -> })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
    @Test fun shortMtuKeepsTheBoundedWindowAndDurableAcknowledgements() = runBlocking {
        image(501) { file, original ->
            val receiver = ByteArrayOutputStream(); var clock = 0L
            val result = FirmwareTransfer({ clock }).send(file, original.size, 44,
                { packet -> clock++; receiver.write(packet, 4, packet.size-4) },
                { ack(receiver.size()) }, { _, _ -> })
            assertArrayEquals(original, receiver.toByteArray()); assertEquals(0, result.retries)
        }
    }
    @Test fun cancellationStopsSending() = runBlocking {
        image(500) { file, _ ->
            var writes = 0
            val failure = runCatching {
                FirmwareTransfer({ 0L }).send(file, 500, 240,
                    { _ -> writes++; throw CancellationException("stop") }, { ack(0) }, { _, _ -> })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException); assertEquals(1, writes)
        }
    }
}
