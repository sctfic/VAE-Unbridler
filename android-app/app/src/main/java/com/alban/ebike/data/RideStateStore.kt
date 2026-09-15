package com.alban.ebike.data

import android.location.Location
import com.alban.ebike.model.AltitudePoint
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Process-local state. A future ride-history module can persist completed tracks separately. */
object RideStateStore {
    private val _state = MutableStateFlow(RideUiState())
    val state = _state.asStateFlow()

    private val profile = ArrayDeque<AltitudePoint>()
    private val track = ArrayDeque<TrackPoint>()
    private var lastLocation: Location? = null
    private var filteredAltitude: Float? = null
    private var distanceM = 0.0

    fun setBluetoothReady(ready: Boolean) {
        _state.value = _state.value.copy(bluetoothReady = ready)
    }

    fun ingestTelemetry(telemetry: BikeTelemetry) {
        _state.value = _state.value.copy(
            wheelSpeedKmh = telemetry.wheelSpeedKmh,
            motorSpeedKmh = telemetry.motorSpeedKmh,
            simulatedOutput = telemetry.simulatedOutput,
        )
    }

    @Synchronized
    fun ingestLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 25f) return
        val previous = lastLocation
        val time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val deltaSeconds = previous?.let { (time - it.time).coerceAtLeast(1L) / 1000f } ?: 0f
        val segmentM = previous?.distanceTo(location)?.toDouble() ?: 0.0

        // Reject GPS jumps while retaining a smooth current-speed display.
        val plausibleSegment = previous == null ||
            (segmentM <= max(35.0, deltaSeconds * 25.0) && segmentM >= 0.0)
        if (!plausibleSegment) return

        if (previous != null && segmentM >= 0.4) distanceM += segmentM
        lastLocation = Location(location)

        val rawAltitude = location.altitude.toFloat()
        val altitude = filteredAltitude?.let { it * 0.80f + rawAltitude * 0.20f } ?: rawAltitude
        filteredAltitude = altitude
        val point = TrackPoint(location.latitude, location.longitude, altitude, time)
        track.addLast(point)
        profile.addLast(AltitudePoint(time, altitude))
        while (profile.isNotEmpty() && time - profile.first().timeMs > 60_000L) profile.removeFirst()
        while (track.size > 180) track.removeFirst()

        val speedKmh = when {
            location.hasSpeed() -> location.speed * 3.6f
            previous != null && deltaSeconds > 0 -> (segmentM / deltaSeconds * 3.6).toFloat()
            else -> 0f
        }.coerceIn(0f, 120f)

        val inclination = calculateInclination(point)
        _state.value = _state.value.copy(
            gpsSpeedKmh = speedKmh,
            altitudeM = altitude,
            inclinePercent = inclination,
            distanceM = distanceM,
            profile = profile.toList(),
            track = track.toList(),
            lastGpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
        )
    }

    private fun calculateInclination(latest: TrackPoint): Float {
        val reference = track.toList().asReversed().firstOrNull { candidate ->
            candidate !== latest && horizontalDistanceM(candidate, latest) >= 12.0
        } ?: return 0f
        val horizontalDistance = horizontalDistanceM(reference, latest)
        if (horizontalDistance < 1.0) return 0f
        return (((latest.altitudeM - reference.altitudeM) / horizontalDistance) * 100f)
            .coerceIn(-25f, 25f)
    }

    private fun horizontalDistanceM(first: TrackPoint, second: TrackPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, results)
        return results[0].toDouble()
    }
}
