package com.alban.ebike.data

import android.location.Location
import android.os.SystemClock
import com.alban.ebike.location.GpsMotionFilter
import com.alban.ebike.location.GpsTrackFilter
import com.alban.ebike.location.GpsDebugLog
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.alban.ebike.scene.VisibleTrack

/** Process-local state. A future ride-history module can persist completed tracks separately. */
object RideStateStore {
    private val _state = MutableStateFlow(RideUiState())
    val state = _state.asStateFlow()

    private val profile = DistanceAltitudeProfile()
    private val track = VisibleTrack()
    private var trackPoints = emptyList<TrackPoint>()
    private var lastFixTimeMs = -1L
    private var filteredAltitude: Float? = null
    private var distanceM = 0.0
    private val regression = com.alban.ebike.location.PositionSpeedRegression()
    private val movingTimer = MovingTimer()
    private val elevationGain = ElevationGain()
    @Volatile var movingThresholdKmh = 4f
    private val motionFilter = GpsMotionFilter()
    private val trackFilter = GpsTrackFilter()
    private var lastReliableSpeedMs = 0L

    @Synchronized
    fun resetRide() {
        track.clear(); trackPoints = emptyList(); profile.clear()
        distanceM = 0.0; filteredAltitude = null
        movingTimer.reset(); elevationGain.reset()
        resetGpsSpeed()
        RideTrackJournal.startNewRide()
        _state.update { it.copy(distanceM = 0.0, track = emptyList(), profile = emptyList(),
            position = null, altitudeM = null, movingTimeMs = 0, elevationGainM = 0f, inclinePercent = 0f, inclineValid = false) }
        GpsDebugLog.record("RESET trajet confirmé")
    }

    @Synchronized
    fun expireGpsSpeed(nowMs: Long = SystemClock.elapsedRealtime()) {
        val elapsed = movingTimer.update(nowMs, _state.value.displayedSpeedKmh.takeIf {
            _state.value.bluetoothReady || nowMs - lastReliableSpeedMs <= 4000
        }, movingThresholdKmh)
        _state.update { it.copy(movingTimeMs = elapsed) }
        if (nowMs - lastReliableSpeedMs > 4000 && _state.value.gpsSpeedValid) {
            _state.update { it.copy(gpsSpeedKmh = 0f, gpsSpeedValid = false,
                gpsStatus = "Signal GPS en attente") }
        }
    }

    @Synchronized
    fun resetGpsSpeed() {
        motionFilter.reset()
        regression.reset()
        trackFilter.reset()
        lastFixTimeMs = -1L
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
        if (fixTimeMs <= lastFixTimeMs) {
            GpsDebugLog.record("fix désordonné ignoré")
            return
        }
        val fix = GpsMotionFilter.Fix(fixTimeMs,
            location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            if (location.hasSpeed()) location.speed else null,
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null)
        var motion = motionFilter.accept(fix, nowMs)
        val decision = trackFilter.accept(fix, nowMs, motion)
        if (!decision.accepted) {
            motionFilter.reset()
            motion = GpsMotionFilter.Result(0f, false, false, decision.reason)
        } else lastFixTimeMs = fixTimeMs
        val estimated = if (decision.accepted) regression.accept(fix, nowMs) else { regression.reset(); null }
        val approximate = (!motion.reliable || motion.reason.startsWith("maintien")) && estimated != null
        if (approximate) motion = GpsMotionFilter.Result(estimated!!, true, estimated > 1.8f, "régression coordonnées · approximation")
        val accuracy = if (location.hasAccuracy()) "%.0f".format(location.accuracy) else "?"
        val rawSpeed = if (location.hasSpeed()) "%.1f".format(location.speed * 3.6f) else "?"
        val speedError = if (location.hasSpeedAccuracy()) "%.2f".format(location.speedAccuracyMetersPerSecond) else "?"
        val diagnostic = "GPS ${location.provider} ±${accuracy}m · âge ${nowMs - fixTimeMs}ms\n" +
            "brut=$rawSpeed km/h ±$speedError m/s\n" +
            "retenu=${if (motion.reliable) "%.1f".format(motion.speedKmh) else "—"} km/h · ${motion.reason}"
        setGpsStatus(diagnostic)
        GpsDebugLog.record("fix altitude=${if (location.hasAltitude()) location.altitude else null} " +
            "speedValid=${motion.reliable} moving=${motion.moving} output=${motion.speedKmh} " +
            "trace=${decision.reason} append=${decision.append} segmentStart=${decision.segmentStart} stepM=${decision.distanceM}")
        if (motion.reliable) lastReliableSpeedMs = fixTimeMs
        _state.update { it.copy(gpsSpeedKmh = motion.speedKmh, gpsSpeedValid = motion.reliable, gpsSpeedApproximate = approximate) }
        if (!decision.accepted) return
        val time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        distanceM += decision.distanceM

        val hasAltitude = location.hasAltitude() && location.altitude.isFinite() &&
            (!location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 15f)
        if (hasAltitude && (filteredAltitude == null || decision.distanceM > 0 || decision.segmentStart)) {
            val rawAltitude = location.altitude.toFloat()
            val alpha = (1 - kotlin.math.exp(-decision.distanceM / 8.0)).toFloat().coerceIn(0f, 1f)
            filteredAltitude = if (decision.segmentStart) rawAltitude else
                filteredAltitude?.let { it + alpha * (rawAltitude - it) } ?: rawAltitude
        }
        if (!hasAltitude) filteredAltitude = null
        val altitude = filteredAltitude
        val profilePoints = if (decision.append || !hasAltitude) profile.update(distanceM,
            altitude.takeIf { hasAltitude }, decision.segmentStart) else _state.value.profile
        if (decision.append || !hasAltitude) elevationGain.update(altitude.takeIf { hasAltitude }, decision.segmentStart)
        val inclination = profile.grade()
        GpsDebugLog.record("profile distanceM=$distanceM grade=$inclination altitude=$altitude append=${decision.append}")
        val point = TrackPoint(location.latitude, location.longitude, altitude ?: 0f, time,
            distanceM, decision.segmentStart, motion.speedKmh.takeIf { motion.reliable }, inclination, hasAltitude)
        if (decision.append) {
            trackPoints = track.add(point)
            RideTrackJournal.record(point)
        }
        _state.update { it.copy(
            gpsStatus = diagnostic,
            gpsSpeedKmh = motion.speedKmh,
            gpsSpeedValid = motion.reliable,
            gpsSpeedApproximate = approximate,
            elevationGainM = elevationGain.metres,
            altitudeM = altitude,
            inclinePercent = inclination ?: 0f,
            inclineValid = inclination != null,
            distanceM = distanceM,
            profile = profilePoints,
            track = trackPoints,
            position = point,
            lastGpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
        ) }
    }

}
