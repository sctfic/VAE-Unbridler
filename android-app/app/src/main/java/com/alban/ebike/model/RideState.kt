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
    val movingTimeMs: Long? = null,
    val restTimeMs: Long? = null,
    val resting: Boolean = false,
    val elevationGainM: Float? = null,
    val speedApproximate: Boolean = false,
    val speedSource: String? = null,
    val accuracyM: Float? = null,
    val rawAltitudeM: Double? = null,
    val rawSpeedKmh: Float? = null,
    val positionValid: Boolean = true,
    val wheelSpeedKmh: Float? = null,
    val motorSpeedKmh: Float? = null,
    val altitudeSource: String? = null,
    val altitudeUncertaintyM: Float? = null,
    val altitudeSegmentStart: Boolean = false,
    val gradeEstimatePercent: Float? = null,
    val elevationLossM: Float? = null,
)


data class RidePause(val start: TrackPoint, val end: TrackPoint? = null)

data class RideUiState(
    val gpsSpeedTimeMs: Long = -1L,
    val wheelTelemetryTimeMs: Long = -1L,
    val wheelTelemetryCoherent: Boolean = true,
    val motorTelemetryCoherent: Boolean = true,
    val speedMode: Boolean = false,
    val modeSupported: Boolean = false,
    val bluetoothReady: Boolean = false,
    val bluetoothStatus: String = "Recherche ESP32",
    val gpsStatus: String = "GPS en attente",
    val gpsSpeedKmh: Float = 0f,
    val gpsSpeedValid: Boolean = false,
    val gpsSpeedApproximate: Boolean = false,
    val movingTimeMs: Long = 0L,
    val restTimeMs: Long = 0L,
    val resting: Boolean = true,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
    val wheelSpeedKmh: Float = 0f,
    val motorSpeedKmh: Float = 0f,
    val altitudeM: Float? = null,
    val altitudeSource: String? = null,
    val altitudeUncertaintyM: Float? = null,
    val inclinePercent: Float = 0f,
    val inclineValid: Boolean = false,
    val distanceM: Double = 0.0,
    val profile: List<AltitudePoint> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val pauses: List<RidePause> = emptyList(),
    val calibration: com.alban.ebike.data.WheelCalibrationProposal? = null,
    val position: TrackPoint? = null,
    val simulatedOutput: Boolean = false,
    val lastGpsAccuracyM: Float? = null,
) {
    private val validWheelSpeed get() = wheelSpeedKmh.takeIf { bluetoothReady && wheelTelemetryCoherent && it.isFinite() && it in 0f..99f }
    private val wheelOverridesGpsZero get() = gpsSpeedValid && gpsSpeedKmh == 0f && (validWheelSpeed ?: 0f) > 0f
    val displayedSpeedKmh: Float? get() = when {
        wheelOverridesGpsZero -> validWheelSpeed
        gpsSpeedValid -> gpsSpeedKmh
        bluetoothReady -> validWheelSpeed
        else -> null
    }
    val displayedSpeedSource: String get() = when {
        wheelOverridesGpsZero -> "ROUE"
        gpsSpeedValid -> "GPS"
        validWheelSpeed != null -> "ROUE"
        else -> "GPS / ROUE —"
    }
}
