package com.alban.ebike.firmware

import com.alban.ebike.model.FirmwareProtocol
import java.io.File
import kotlinx.coroutines.*
import java.util.Locale

fun firmwareTransferDetail(received: Int, total: Int, kilobytesPerSecond: Double): String {
    require(total > 0 && received in 0..total)
    val rate = String.format(Locale.FRANCE, "%.1f", kilobytesPerSecond)
    return "Transfert : $received / $total octets (${received.toLong() * 100 / total} %) · $rate Ko/s"
}

/** Continuous credit window. Only durable ESP notifications grant new credit. */
class FirmwareTransfer(private val nowMs: () -> Long) {
    data class Result(val bytes: Int, val durationMs: Long, val retries: Int)
    suspend fun send(file: File, size: Int, chunkSize: Int,
        write: suspend (ByteArray) -> Unit,
        acknowledge: suspend (Int) -> com.alban.ebike.model.FirmwareProgress,
        progress: (Int, Double) -> Unit): Result = withContext(Dispatchers.IO) {
        require(size > 0 && file.length() == size.toLong() && chunkSize in 1..508)
        val image = file.readBytes()
        val start = nowMs()
        var durable = 0
        var sent = 0
        var retries = 0
        var stalledRetries = 0
        var lastPublished = start
        while (durable < size) {
            val limit = minOf(size, durable + FirmwareProtocol.STREAM_WINDOW_BYTES)
            while (sent < limit) {
                currentCoroutineContext().ensureActive()
                val length = minOf(chunkSize, size - sent)
                if (sent + length > limit) break // do not spend an Android callback on a tiny credit-boundary packet
                write(FirmwareProtocol.chunk(sent, image.copyOfRange(sent, sent + length), length))
                sent += length
            }
            val ack = acknowledge(durable)
            require(ack.state == 1 && ack.error == 0 && ack.size == size && ack.received in durable..sent) {
                if (ack.error != 0) FirmwareProtocol.errorMessage(ack.error) else "Progression ESP32 incohérente."
            }
            if (ack.received == durable) {
                check(++stalledRetries <= 3) { "Transfert Bluetooth bloqué : relancez la mise à jour." }
                retries++; sent = durable
            } else {
                durable = ack.received; stalledRetries = 0
                val now = nowMs()
                if (durable == size || now - lastPublished >= 100) {
                    progress(durable, durable / maxOf(1L, now - start).toDouble())
                    lastPublished = now
                }
            }
        }
        Result(durable, nowMs() - start, retries)
    }
}
