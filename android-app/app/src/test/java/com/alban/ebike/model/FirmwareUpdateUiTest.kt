package com.alban.ebike.model

import com.alban.ebike.firmware.FirmwareRelease
import com.alban.ebike.firmware.FirmwareUpdateUi
import com.alban.ebike.firmware.UpdatePhase
import org.junit.Assert.*
import org.junit.Test

class FirmwareUpdateUiTest {
    private val info = FirmwareInfo(0, 0, 7, 0, 0, 10, 0, 1, 0x1e0000, "0.4.9", FirmwareProtocol.hardwareRevision)
    private val release = FirmwareRelease("0.4.10", 11, 100, "0".repeat(64), "unused")

    @Test fun offersOnlyHigherVersionCodesOnCompatibleFirmware() {
        val ui = FirmwareUpdateUi(info, release)
        assertTrue(ui.available); assertTrue(ui.canInstall)
        assertFalse(ui.copy(release = release.copy(versionCode = 10)).available)
        assertFalse(ui.copy(release = release.copy(versionCode = 9)).available)
        assertFalse(ui.copy(info = null).available)
        assertFalse(ui.copy(info = info.copy(hardwareRevision = 0)).available)
        assertFalse(ui.copy(info = info.copy(targetSlot = 0)).available)
    }

    @Test fun requiresImmobilityAndConfirmedBootBeforeInstallation() {
        val ui = FirmwareUpdateUi(info, release)
        assertFalse(ui.copy(info = info.copy(flags = 5)).canInstall)
        assertFalse(ui.copy(info = info.copy(flags = 3)).canInstall)
        assertFalse(ui.copy(phase = UpdatePhase.TRANSFERRING).canInstall)
        assertTrue(ui.copy(phase = UpdatePhase.ERROR).canInstall)
    }
}
