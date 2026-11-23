package com.alban.ebike.firmware

import com.alban.ebike.model.FirmwareInfo
import com.alban.ebike.model.FirmwareProtocol
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*

/** Bounded windows, persisted-offset ACKs and bounded retransmissions. No GPS dependency. */
class FirmwareTransfer(private val nowMs: () -> Long, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    data class Result(val bytes: Int, val durationMs: Long, val retries: Int)
    suspend fun send(file: File, size: Int, chunkSize: Int,
        write: suspend (ByteArray) -> Unit, acknowledge: suspend () -> FirmwareInfo,
        progress: (Int, Double) -> Unit): Result {
        require(size > 0 && file.length() == size.toLong() && chunkSize in 1..240)
        val start = nowMs()
        var durable = 0
        var retries = 0
        val windowSize = minOf(FirmwareProtocol.STREAM_WINDOW_BYTES,
            chunkSize * FirmwareProtocol.STREAM_WINDOW_PACKETS)
        RandomAccessFile(file, "r").use { image ->
            while (durable < size) {
                val base = durable
                val end = minOf(size, base + windowSize)
                val block = ByteArray(end - base)
                withContext(Dispatchers.IO) { image.seek(base.toLong()); image.readFully(block) }
                var sendFrom = base
                var lastProgress: Long
                var windowRetries = 0
                while (durable < end) {
                    var offset = sendFrom
                    while (offset < end) {
                        currentCoroutineContext().ensureActive()
                        val length = minOf(chunkSize, end - offset)
                        val payload = block.copyOfRange(offset - base, offset - base + length)
                        // NO_RESPONSE completion only drains the local Android queue.
                        write(FirmwareProtocol.chunk(offset, payload, length))
                        offset += length
                    }
                    lastProgress = nowMs()
                    var resend = false
                    while (durable < end && !resend) {
                        val ack = acknowledge()
                        require(ack.state == 1 && ack.received in durable..end) { "Progression ESP32 incohérente." }
                        if (ack.received > durable) { durable = ack.received; lastProgress = nowMs() }
                        if (durable < end) {
                            if (nowMs() - lastProgress >= 1_500) {
                                check(++windowRetries <= 3) { "Transfert Bluetooth bloqué : relancez la mise à jour." }
                                retries++; sendFrom = durable; resend = true
                            } else pause(25)
                        }
                    }
                }
                val seconds = maxOf(1L, nowMs() - start) / 1000.0
                progress(durable, durable / 1000.0 / seconds)
            }
        }
        return Result(durable, nowMs() - start, retries)
    }
}
