package com.alban.ebike.firmware

import com.alban.ebike.model.FirmwareProgress
import com.alban.ebike.model.FirmwareProtocol
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FirmwareTransferTest {
    private fun ack(received: Int, size: Int, state: Int = 1, error: Int = 0) =
        FirmwareProgress(state, error, 7, received, size)
    private suspend fun image(size: Int, block: suspend (File, ByteArray) -> Unit) {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val file = File.createTempFile("ota-transfer", ".bin")
        try { file.writeBytes(bytes); block(file, bytes) } finally { file.delete() }
    }
    private fun offset(packet: ByteArray) = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int

    @Test fun creditWindowReconstructsTheImageWithFewerAcknowledgements() = runBlocking {
        image(445056) { file, original ->
            val receiver = ByteArrayOutputStream(); var clock = 0L; var writes = 0; var acks = 0; var progress = 0
            val result = FirmwareTransfer { clock }.send(file, original.size, 508,
                { packet ->
                    assertEquals(receiver.size(), offset(packet)); writes++; clock++
                    receiver.write(packet, 4, packet.size - 4)
                }, { after ->
                    assertTrue(receiver.size() - after <= FirmwareProtocol.STREAM_WINDOW_BYTES)
                    acks++; ack(receiver.size(), original.size)
                }, { durable, _ -> progress = durable })
            assertArrayEquals(original, receiver.toByteArray())
            assertEquals(original.size, result.bytes); assertEquals(original.size, progress)
            assertEquals(0, result.retries); assertTrue(writes > acks * 20)
        }
    }
    @Test fun partialDurableAckGrantsOnlyItsNewCredit() = runBlocking {
        image(40001) { file, original ->
            val receiver = ByteArrayOutputStream(); var durable = 0; var clock = 0L
            FirmwareTransfer { clock }.send(file, original.size, 508,
                { packet ->
                    assertTrue(offset(packet) + packet.size - 4 <= durable + FirmwareProtocol.STREAM_WINDOW_BYTES)
                    assertEquals(receiver.size(), offset(packet))
                    receiver.write(packet, 4, packet.size - 4); clock++
                }, { _ -> durable = minOf(receiver.size(), durable + 4096); ack(durable, original.size) }, { _, _ -> })
            assertArrayEquals(original, receiver.toByteArray())
        }
    }
    @Test fun aMissingPacketRetransmitsFromTheCommittedOffset() = runBlocking {
        image(20001) { file, original ->
            val receiver = ByteArrayOutputStream(); var clock = 0L; var dropped = false
            val result = FirmwareTransfer { clock }.send(file, original.size, 508,
                { packet ->
                    val at = offset(packet)
                    if (at == 508 && !dropped) dropped = true
                    else if (at == receiver.size()) receiver.write(packet, 4, packet.size - 4)
                }, { after ->
                    if (after == receiver.size()) clock += 1500
                    ack(receiver.size(), original.size)
                }, { _, _ -> })
            assertTrue(dropped); assertEquals(1, result.retries)
            assertArrayEquals(original, receiver.toByteArray())
        }
    }
    @Test fun queuedBytesAreNotReportedBeforeTheDurableAck() = runBlocking {
        image(500) { file, _ ->
            var progress = 0
            FirmwareTransfer { 0L }.send(file, 500, 508,
                { _ -> assertEquals(0, progress) }, { _ -> assertEquals(0, progress); ack(500, 500) },
                { durable, _ -> progress = durable })
            assertEquals(500, progress)
        }
    }
    @Test fun stalledReceiverFailsAfterBoundedRetries() = runBlocking {
        image(500) { file, _ ->
            var clock = 0L; var writes = 0
            val failure = runCatching {
                FirmwareTransfer { clock }.send(file, 500, 508,
                    { _ -> writes++ }, { _ -> clock += 1500; ack(0, 500) }, { _, _ -> fail() })
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException); assertEquals(4, writes); assertEquals(6000L, clock)
        }
    }
    @Test fun invalidOrFailedAcknowledgementsAbort() = runBlocking {
        for (reply in listOf(ack(501, 501), ack(0, 500, 4, 1), ack(0, 499))) image(500) { file, _ ->
            val failure = runCatching {
                FirmwareTransfer { 0L }.send(file, 500, 508, { _ -> }, { _ -> reply }, { _, _ -> })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
    @Test fun negotiatedShortMtuStillRespectsCredit() = runBlocking {
        image(501) { file, original ->
            val receiver = ByteArrayOutputStream()
            FirmwareTransfer { 0L }.send(file, original.size, 44,
                { packet -> receiver.write(packet, 4, packet.size - 4) },
                { _ -> ack(receiver.size(), original.size) }, { _, _ -> })
            assertArrayEquals(original, receiver.toByteArray())
        }
    }
    @Test fun cancellationStopsSending() = runBlocking {
        image(500) { file, _ ->
            var writes = 0
            val failure = runCatching {
                FirmwareTransfer { 0L }.send(file, 500, 508,
                    { _ -> writes++; throw CancellationException("stop") }, { _ -> ack(0, 500) }, { _, _ -> })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException); assertEquals(1, writes)
        }
    }
}
