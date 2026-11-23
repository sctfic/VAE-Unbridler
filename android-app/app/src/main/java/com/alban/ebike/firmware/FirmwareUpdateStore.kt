package com.alban.ebike.firmware

import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.FirmwareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class UpdatePhase { IDLE, WAKING, DOWNLOADING, PREPARING, TRANSFERRING, VERIFYING, RESTARTING, CONFIRMING, SUCCESS, ERROR }

data class FirmwareRelease(val version: String, val versionCode: Int, val size: Int, val sha256: String, val url: String)

data class FirmwareUpdateUi(
    val info: FirmwareInfo? = null,
    val release: FirmwareRelease? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Float = 0f,
    val detail: String = "Connexion à l’ESP32 nécessaire pour connaître sa version.",
    val checking: Boolean = false,
) {
    val busy get() = phase in UpdatePhase.WAKING..UpdatePhase.CONFIRMING
    val available get() = info?.let { it.supported && release != null && release.versionCode > it.versionCode } == true
    val canInstall get() = available && !busy && info?.stationary == true && info.bootConfirmed
    fun canInstallFor(ride: RideUiState) = canInstall && ride.bluetoothReady
}

object FirmwareUpdateStore {
    private val mutable = MutableStateFlow(FirmwareUpdateUi())
    val state = mutable.asStateFlow()
    fun update(block: (FirmwareUpdateUi) -> FirmwareUpdateUi) { mutable.update(block) }
    fun info(value: FirmwareInfo?) { mutable.update { it.copy(info = value) } }
}
