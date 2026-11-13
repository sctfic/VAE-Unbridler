package com.alban.ebike.data

import android.content.Context
import com.alban.ebike.model.BleProtocol
import kotlinx.coroutines.flow.map

class BikeSettingsStore internal constructor() {
    constructor(context: Context) : this() { BikeProfiles.initialize(context) }
    val circumferenceMm = BikeProfiles.revision.map { currentCircumference() }
    fun currentCircumference(id: String = BikeProfiles.activeId.value) =
        BikeProfiles.preferences("settings", id).getInt("circumference_mm", BleProtocol.defaultCircumferenceMm)
    fun initializeWheel(reported: Int, id: String): Int {
        val preferences = BikeProfiles.preferences("settings", id)
        if (!preferences.contains("circumference_mm") && reported in 1000..4000) setCircumferenceMm(reported, id)
        return currentCircumference(id)
    }
    fun setCircumferenceMm(value: Int, id: String = BikeProfiles.activeId.value) {
        require(value in 1000..4000)
        BikeProfiles.preferences("settings", id).edit().putInt("circumference_mm", value).apply()
        BikeProfiles.changed()
    }
}
