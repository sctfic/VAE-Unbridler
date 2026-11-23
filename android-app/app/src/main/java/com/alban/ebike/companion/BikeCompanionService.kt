package com.alban.ebike.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import com.alban.ebike.service.RideService

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@RequiresApi(Build.VERSION_CODES.S)
class BikeCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        appeared()
    }

    override fun onDeviceAppeared(address: String) {
        appeared()
    }

    private fun appeared() {
        if (PresenceWatchdog.blocked(this)) return
        RideService.start(this)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        PresenceWatchdog.rearm(this)
        BikeArrivalNotification.dismiss(this)
        // GPS remains independent of the companion's presence.
    }

    override fun onDeviceDisappeared(address: String) {
        PresenceWatchdog.rearm(this)
        BikeArrivalNotification.dismiss(this)
    }
}
