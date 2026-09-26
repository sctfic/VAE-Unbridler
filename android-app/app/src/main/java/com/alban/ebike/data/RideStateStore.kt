package com.alban.ebike.data

import android.location.Location
import android.os.SystemClock
import com.alban.ebike.location.GpsMotionFilter
import com.alban.ebike.location.GpsDebugLog
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/** Process-local state. A future ride-history module can persist completed tracks separately. */
object RideStateStore {
    private val _state = MutableStateFlow(RideUiState())
    val state = _state.asStateFlow()

    private val profile = DistanceAltitudeProfile()
    private val track = ArrayDeque<TrackPoint>()
    private var lastLocation: Location? = null
    private var filteredAltitude: Float? = null
    private var distanceM = 0.0
    private val motionFilter = GpsMotionFilter()
    private var lastReliableSpeedMs = 0L

    @Synchronized
    fun expireGpsSpeed(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (nowMs - lastReliableSpeedMs > 4000 && _state.value.gpsSpeedValid) {
            motionFilter.reset()
            _state.update { it.copy(gpsSpeedKmh = 0f, gpsSpeedValid = false,
                gpsStatus = "Signal GPS en attente") }
        }
    }

    @Synchronized
    fun resetGpsSpeed() {
        motionFilter.reset()
        lastLocation = null
        lastReliableSpeedMs = 0
        _state.update { it.copy(gpsSpeedKmh = 0f, gpsSpeedValid = false) }
    }

    fun setBluetoothReady(ready: Boolean) {
        _state.update { it.copy(bluetoothReady = ready,
            modeSupported = ready && it.modeSupported,
            speedMode = ready && it.speedMode,
            wheelSpeedKmh = if (ready) it.wheelSpeedKmh else 0f,
            motorSpeedKmh = if (ready) it.motorSpeedKmh else 0f,
            simulatedOutput = ready && it.simulatedOutput) }
    }

    fun setBluetoothStatus(message: String) { _state.update { it.copy(bluetoothStatus = message) } }
    fun setGpsStatus(message: String) {
        GpsDebugLog.record(message)
        _state.update { it.copy(gpsStatus = message) }
    }

    fun ingestTelemetry(telemetry: BikeTelemetry) {
        _state.update { it.copy(
            speedMode = telemetry.speedMode,
            modeSupported = telemetry.modeSupported,
            wheelSpeedKmh = telemetry.wheelSpeedKmh,
            motorSpeedKmh = telemetry.motorSpeedKmh,
            simulatedOutput = telemetry.simulatedOutput,
        ) }
    }

    @Synchronized
    fun ingestLocation(location: Location) {
        val nowMs = SystemClock.elapsedRealtime()
        val fixTimeMs = location.elapsedRealtimeNanos / 1_000_000
        val motion = motionFilter.accept(GpsMotionFilter.Fix(fixTimeMs,
            location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            if (location.hasSpeed()) location.speed else null,
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null), nowMs)
        val accuracy = if (location.hasAccuracy()) "%.0f".format(location.accuracy) else "?"
        val rawSpeed = if (location.hasSpeed()) "%.1f".format(location.speed * 3.6f) else "?"
        val speedError = if (location.hasSpeedAccuracy()) "%.2f".format(location.speedAccuracyMetersPerSecond) else "?"
        val diagnostic = "GPS ${location.provider} ±${accuracy}m · âge ${nowMs - fixTimeMs}ms\n" +
            "v=$rawSpeed km/h ±$speedError m/s · ${motion.reason}"
        setGpsStatus(diagnostic)
        GpsDebugLog.record("fix altitude=${if (location.hasAltitude()) location.altitude else null} " +
            "speedValid=${motion.reliable} moving=${motion.moving} output=${motion.speedKmh}")
        if (motion.reliable) lastReliableSpeedMs = fixTimeMs
        _state.update { it.copy(gpsSpeedKmh = motion.speedKmh, gpsSpeedValid = motion.reliable) }
        if (nowMs - fixTimeMs !in 0L..4000L || !location.hasAccuracy() || location.accuracy > 20f) {
            lastLocation = null
            return
        }
        val previous = lastLocation
        val time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val deltaSeconds = previous?.let {
            ((location.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000_000f)
        } ?: 0f
        val segmentM = previous?.distanceTo(location)?.toDouble() ?: 0.0

        // Reject GPS jumps while retaining a smooth current-speed display.
        val plausibleSegment = previous == null ||
            (segmentM <= max(35.0, deltaSeconds * 25.0) && segmentM >= 0.0)
        if (!plausibleSegment || (previous != null && deltaSeconds <= 0)) {
            resetGpsSpeed()
            setGpsStatus("$diagnostic\nPosition instable : segment=${segmentM}m dt=${deltaSeconds}s")
            return
        }

        if (motion.moving && previous != null && deltaSeconds <= 4f && segmentM >= 0.4) distanceM += segmentM
        lastLocation = if (motion.moving) Location(location) else null

        if (location.hasAltitude()) {
            val rawAltitude = location.altitude.toFloat()
            filteredAltitude = filteredAltitude?.let { it * 0.80f + rawAltitude * 0.20f } ?: rawAltitude
        }
        val altitude = filteredAltitude
        val point = TrackPoint(location.latitude, location.longitude, altitude ?: 0f, time)
        if (track.isEmpty() || motion.moving) track.addLast(point)
        val profilePoints = profile.update(distanceM, altitude.takeIf { location.hasAltitude() })
        while (track.size > 180) track.removeFirst()

        val inclination = if (altitude != null) calculateInclination(point) else 0f
        _state.update { it.copy(
            gpsStatus = diagnostic,
            gpsSpeedKmh = motion.speedKmh,
            gpsSpeedValid = motion.reliable,
            altitudeM = altitude,
            inclinePercent = inclination,
            distanceM = distanceM,
            profile = profilePoints,
            track = track.toList(),
            lastGpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
        ) }
    }

    private fun calculateInclination(latest: TrackPoint): Float {
        val reference = track.toList().asReversed().firstOrNull { candidate ->
            candidate !== latest && horizontalDistanceM(candidate, latest) >= 12.0
        } ?: return 0f
        val horizontalDistance = horizontalDistanceM(reference, latest)
        if (horizontalDistance < 1.0) return 0f
        return (((latest.altitudeM - reference.altitudeM) / horizontalDistance.toFloat()) * 100f)
            .coerceIn(-25f, 25f)
    }

    private fun horizontalDistanceM(first: TrackPoint, second: TrackPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, results)
        return results[0].toDouble()
    }
}
