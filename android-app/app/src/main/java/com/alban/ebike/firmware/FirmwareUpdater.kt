package com.alban.ebike.firmware

import com.alban.ebike.bluetooth.BikeGattClient
import com.alban.ebike.data.RideStateStore
import com.alban.ebike.model.FirmwareInfo
import com.alban.ebike.model.FirmwareProtocol
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Runs in RideService, so rotation/backgrounding the dashboard does not stop OTA. */
class FirmwareUpdater(private val scope: CoroutineScope, private val client: BikeGattClient, private val cacheDir: File) {
    private val repository = GithubFirmwareRepository()
    private var checking: Job? = null
    private var installing: Job? = null
    private var lastCheckMs = 0L

    init {
        scope.launch {
            while (isActive) {
                delay(2_000)
                if (client.ready && !FirmwareUpdateStore.state.value.busy) {
                    runCatching { client.readFirmwareInfo() }
                    check()
                }
            }
        }
    }

    fun connected() {
        if (!FirmwareUpdateStore.state.value.busy) check(force = true)
    }

    fun check(force: Boolean = false) {
        if (checking?.isActive == true || FirmwareUpdateStore.state.value.busy) return
        if (!force && System.currentTimeMillis() - lastCheckMs < 3_600_000) return
        lastCheckMs = System.currentTimeMillis()
        checking = scope.launch {
            FirmwareUpdateStore.update { it.copy(checking = true) }
            try {
                val release = withContext(Dispatchers.IO) { repository.latest() }
                FirmwareUpdateStore.update { it.copy(release = release,
                    detail = if (release == null) "Aucun firmware OTA publié sur GitHub." else "Firmware GitHub vérifié : ${release.version}.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { FirmwareUpdateStore.update { it.copy(detail = e.message ?: "Vérification GitHub impossible.") } }
            finally { FirmwareUpdateStore.update { it.copy(checking = false) } }
        }
    }

    fun install() {
        if (installing?.isActive == true || !FirmwareUpdateStore.state.value.canInstall) return
        val release = FirmwareUpdateStore.state.value.release ?: return
        phase(UpdatePhase.WAKING, "Contrôle d’immobilité avant la mise à jour…")
        installing = scope.launch {
            checking?.cancel()
            var activated = false
            val file = File(cacheDir, "esp32-ota-${UUID.randomUUID()}.bin")
            try {
                client.lockFirmwareDevice(true)
                val old = info(allowError = true)
                require(old.supported && old.bootConfirmed && release.versionCode > old.versionCode && release.size <= old.capacity) {
                    "Firmware incompatible ou trop volumineux pour cet ESP32."
                }
                requireStationary(old)
                if (old.wakeSupported) {
                    phase(UpdatePhase.WAKING, "Maintien de l’alimentation : trois impulsions lentes à 2 km/h…")
                    client.firmwareControl(byteArrayOf(FirmwareProtocol.PREPARE_WAKE))
                    withTimeout(25_000) {
                        while (true) {
                            val wake = info()
                            requireStationary(wake)
                            require(wake.state == 5) { "Préparation de la mise à jour interrompue." }
                            if (wake.wakeComplete) break
                            delay(250)
                        }
                    }
                }
                phase(UpdatePhase.DOWNLOADING, "Téléchargement GitHub et contrôle SHA-256…")
                coroutineScope {
                    val downloadDone = CompletableDeferred<Unit>()
                    val heartbeat = if (old.wakeSupported) launch {
                        while (!downloadDone.isCompleted) {
                            withTimeoutOrNull(5_000) { downloadDone.await() }
                            if (!downloadDone.isCompleted) {
                                requireStationary(info())
                                // Refresh the preparation timeout without repeating its three pulses.
                                client.firmwareControl(byteArrayOf(FirmwareProtocol.PREPARE_WAKE))
                            }
                        }
                    } else null
                    try {
                        withContext(Dispatchers.IO) {
                            repository.download(release, file) { received ->
                                ensureActive()
                                requireStationaryGps()
                                FirmwareUpdateStore.update { it.copy(progress = received.toFloat() / release.size,
                                    detail = "Téléchargement GitHub : $received / ${release.size} octets · SHA-256 en cours") }
                            }
                        }
                    } finally {
                        downloadDone.complete(Unit)
                        // Let any in-flight GATT operation finish before BEGIN; cancelling it
                        // would deliberately disconnect the client to reject stale callbacks.
                        heartbeat?.join()
                    }
                }
                phase(UpdatePhase.PREPARING, "Contrôle d’immobilité · préparation de l’installation…")
                requireStationary(info(allowError = true))
                client.firmwareControl(FirmwareProtocol.begin(release.size, release.sha256, release.versionCode))
                require(info().state == 1) { "L’ESP32 n’a pas préparé l’installation." }
                phase(UpdatePhase.TRANSFERRING, "Transfert Bluetooth vers l’ESP32…")
                file.inputStream().use { input ->
                    var offset = 0
                    val buffer = ByteArray(client.firmwareChunkSize)
                    var lastStatusOffset = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val length = input.read(buffer)
                        if (length < 0) break
                        requireStationaryGps()
                        client.firmwareData(FirmwareProtocol.chunk(offset, buffer, length))
                        offset += length
                        FirmwareUpdateStore.update { it.copy(progress = offset.toFloat() / release.size,
                            detail = "Transfert : $offset / ${release.size} octets (${offset * 100 / release.size} %)" ) }
                        if (offset - lastStatusOffset >= 16_384 || offset == release.size) {
                            val status = info()
                            require(status.state == 1 && status.received == offset) { "Progression ESP32 incohérente." }
                            lastStatusOffset = offset
                        }
                    }
                }
                phase(UpdatePhase.VERIFYING, "Vérification SHA-256 sur l’ESP32 et validation de l’image…")
                client.firmwareControl(byteArrayOf(FirmwareProtocol.FINISH))
                require(info().state == 2) { "Le firmware n’a pas été validé par l’ESP32." }
                requireStationaryGps()
                val previousConnection = client.connectionEpoch
                phase(UpdatePhase.RESTARTING, "Redémarrage et reconnexion de l’ESP32…")
                client.firmwareControl(byteArrayOf(FirmwareProtocol.ACTIVATE))
                activated = true
                withTimeout(90_000) {
                    // Do not mistake the old connection's status for the new boot.
                    while (!client.ready || client.connectionEpoch == previousConnection) { delay(250) }
                    phase(UpdatePhase.CONFIRMING, "ESP32 reconnecté · vérification du nouveau démarrage (dix secondes)…")
                    while (true) {
                        val boot = info()
                        require(boot.versionCode == release.versionCode && boot.version == release.version && boot.slot == old.targetSlot) {
                            "Le firmware attendu ne fonctionne pas : retour possible à la version précédente. Vérifiez l’ESP32."
                        }
                        if (boot.bootConfirmed) break
                        delay(1_000)
                    }
                }
                phase(UpdatePhase.SUCCESS, "Firmware ${release.version} actif et confirmé.", 1f)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { abortUnlessActivated(activated) }
                phase(UpdatePhase.ERROR, "Mise à jour interrompue. Reconnectez l’ESP32 pour vérifier sa version.")
                throw e
            } catch (e: Exception) {
                val status = withContext(NonCancellable) { runCatching { client.readFirmwareInfo() }.getOrNull() }
                withContext(NonCancellable) { abortUnlessActivated(activated) }
                phase(UpdatePhase.ERROR, if (status?.state == 4) FirmwareProtocol.errorMessage(status.error)
                    else e.message ?: "Installation impossible. Vérifiez l’alimentation et la connexion Bluetooth.")
            } finally {
                file.delete()
                client.lockFirmwareDevice(false)
            }
        }
    }

    private suspend fun abortUnlessActivated(activated: Boolean) {
        if (!activated && client.ready) runCatching { client.firmwareControl(byteArrayOf(FirmwareProtocol.ABORT)) }
    }

    private suspend fun info(allowError: Boolean = false): FirmwareInfo {
        val status = client.readFirmwareInfo() ?: error("Première installation OTA par USB nécessaire.")
        if (!allowError && status.state == 4) error(FirmwareProtocol.errorMessage(status.error))
        return status
    }

    private fun requireStationary(info: FirmwareInfo) {
        require(info.stationary) { "Immobilisez le VAE pendant dix secondes avant la mise à jour." }
        requireStationaryGps()
    }

    private fun requireStationaryGps() {
        val ride = RideStateStore.state.value
        require(!ride.gpsSpeedValid || ride.gpsSpeedKmh < 1f) { "Mouvement GPS détecté : mise à jour annulée." }
    }

    private fun phase(phase: UpdatePhase, detail: String, progress: Float = 0f) {
        FirmwareUpdateStore.update { it.copy(phase = phase, detail = detail, progress = progress) }
    }
}
