package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class FirmwareInfo(
    val state: Int, val error: Int, val flags: Int, val received: Int, val size: Int,
    val versionCode: Int, val slot: Int, val targetSlot: Int, val capacity: Int, val version: String, val hardwareRevision: Int = 0,
) {
    val wakeComplete get() = flags and 16 != 0
    val stationary get() = flags and 2 != 0
    val bootConfirmed get() = flags and 4 != 0
    val supported get() = hardwareRevision == FirmwareProtocol.hardwareRevision && flags and 1 != 0 && slot in 0..1 && targetSlot in 0..1 && slot != targetSlot
}

data class FirmwareProgress(val state: Int, val error: Int, val targetCode: Int,
    val received: Int, val size: Int, val intervalUnits: Int = 0, val txPhy: Int = 1, val rxPhy: Int = 1)

object FirmwareProtocol {
    val controlUuid: UUID = UUID.fromString("a6f50005-9b8a-4d9f-a0da-9e9d873d1101")
    val dataUuid: UUID = UUID.fromString("a6f50006-9b8a-4d9f-a0da-9e9d873d1101")
    const val hardwareRevision = 0x0100 // Hardware V1.0, GPIO6 open drain
    const val FINISH: Byte = 2
    const val ABORT: Byte = 3
    const val ACTIVATE: Byte = 4
    const val PREPARE_WAKE: Byte = 5
    const val BEGIN_FAST: Byte = 6
    const val STREAM_WINDOW_BYTES = 16384

    fun decode(bytes: ByteArray): FirmwareInfo? {
        if (bytes.size != 48 || bytes[0].toInt() != 1) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.get()
        val state = b.get().toInt() and 255
        val error = b.get().toInt() and 255
        val flags = b.get().toInt() and 255
        val received = b.int
        val size = b.int
        val code = b.int
        val slot = b.get().toInt() and 255
        val target = b.get().toInt() and 255
        val hardware = b.short.toInt() and 0xffff
        val capacity = b.int
        val version = ByteArray(24).also { b.get(it) }.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        if (state !in 0..5 || error !in 0..6 || code < 1 || capacity <= 0 || received < 0 || size < received) return null
        return FirmwareInfo(state, error, flags, received, size, code, slot, target, capacity, version, hardware)
    }

    fun decodeProgress(bytes: ByteArray): FirmwareProgress? {
        if (bytes.size != 20 || bytes[0].toInt() != 1 || bytes[3].toInt() != 0) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.get(); val state = b.get().toInt() and 255; val error = b.get().toInt() and 255; b.get()
        val code = b.int; val received = b.int; val size = b.int
        val interval = b.short.toInt() and 65535
        val tx = b.get().toInt() and 255; val rx = b.get().toInt() and 255
        if (state !in 0..5 || error !in 0..6 || code < 0 || received < 0 || size < received) return null
        return FirmwareProgress(state, error, code, received, size, interval, tx, rx)
    }

    fun begin(size: Int, sha256: String, versionCode: Int): ByteArray {
        require(size > 0 && versionCode > 0 && sha256.matches(Regex("[a-fA-F0-9]{64}")))
        return ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(BEGIN_FAST); putInt(size)
            sha256.chunked(2).forEach { put(it.toInt(16).toByte()) }
            putInt(versionCode)
        }.array()
    }

    fun chunk(offset: Int, bytes: ByteArray, length: Int): ByteArray {
        require(offset >= 0 && length in 1..508 && length <= bytes.size)
        return ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(offset).put(bytes, 0, length).array()
    }

    fun errorMessage(error: Int) = when (error) {
        1 -> "Mouvement de la roue détecté : installation annulée. Immobilisez le VAE pendant dix secondes."
        2 -> "Firmware ou ordre des paquets incompatible."
        3 -> "Échec de l’écriture ou de la validation du firmware."
        4 -> "Contrôle SHA-256 incorrect : firmware refusé."
        5 -> "Transfert interrompu trop longtemps."
        6 -> "Installation annulée ou Bluetooth interrompu."
        else -> "Commande de mise à jour refusée par l’ESP32."
    }
}
