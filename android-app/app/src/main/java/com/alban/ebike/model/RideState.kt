package com.alban.ebike.model

data class AltitudePoint(val distanceM: Double, val altitudeM: Float, val segmentStart: Boolean = false)

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val timeMs: Long,
    val distanceM: Double = 0.0,
    val segmentStart: Boolean = false,
    val speedKmh: Float? = null,
    val gradePercent: Float? = null,
    val altitudeValid: Boolean = true,
)

data class RideUiState(
    val speedMode: Boolean = false,
    val modeSupported: Boolean = false,
    val bluetoothReady: Boolean = false,
    val bluetoothStatus: String = "Recherche ESP32",
    val gpsStatus: String = "GPS en attente",
    val gpsSpeedKmh: Float = 0f,
    val gpsSpeedValid: Boolean = false,
    val gpsSpeedApproximate: Boolean = false,
    val movingTimeMs: Long = 0L,
    val elevationGainM: Float = 0f,
    val wheelSpeedKmh: Float = 0f,
    val motorSpeedKmh: Float = 0f,
    val altitudeM: Float? = null,
    val inclinePercent: Float = 0f,
    val inclineValid: Boolean = false,
    val distanceM: Double = 0.0,
    val profile: List<AltitudePoint> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val position: TrackPoint? = null,
    val simulatedOutput: Boolean = false,
    val lastGpsAccuracyM: Float? = null,
) {
    val displayedSpeedKmh: Float? get() = when {
        gpsSpeedValid -> gpsSpeedKmh
        bluetoothReady -> wheelSpeedKmh
        else -> null
    }
    val displayedSpeedSource: String get() = when {
        gpsSpeedValid -> "GPS"
        bluetoothReady -> "ROUE"
        else -> "GPS / ROUE —"
    }
}
