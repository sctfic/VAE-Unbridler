package com.alban.ebike.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import com.alban.ebike.service.RideService

@RequiresApi(Build.VERSION_CODES.S)
class BikeCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        RideService.start(this)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Keep the service alive briefly: BikeGattClient owns reconnect handling
        // and GPS stops itself when the BLE link is truly disconnected.
    }
}
