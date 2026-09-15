package com.alban.ebike.model

data class AltitudePoint(val timeMs: Long, val altitudeM: Float)

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val timeMs: Long,
)

data class RideUiState(
    val bluetoothReady: Boolean = false,
    val gpsSpeedKmh: Float = 0f,
    val wheelSpeedKmh: Float = 0f,
    val motorSpeedKmh: Float = 0f,
    val altitudeM: Float? = null,
    val inclinePercent: Float = 0f,
    val distanceM: Double = 0.0,
    val profile: List<AltitudePoint> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val simulatedOutput: Boolean = false,
    val lastGpsAccuracyM: Float? = null,
)
