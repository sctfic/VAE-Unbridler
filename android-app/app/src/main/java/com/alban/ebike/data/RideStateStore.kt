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
    private val altitudeTrend = AltitudeTrendFilter()
    private var altitudeSource: String? = null
    private var elevationGrid: com.alban.ebike.terrain.TerrainGrid? = null
    @Synchronized fun setElevationGrid(grid: com.alban.ebike.terrain.TerrainGrid?) { elevationGrid = grid }
    private var distanceM = 0.0
    private val measurements = com.alban.ebike.location.GpsMeasurements()
    private val altitudeQuality = com.alban.ebike.location.AltitudeQuality()
    private val movingTimer = MovingTimer()
    private val calibration = WheelCalibration()
    private var calibrationBike: String? = null

    @Synchronized fun activateBike(id: String) {
        if (calibrationBike != id) {
            calibration.reset()
            _state.update { it.copy(calibration = null) }
            calibrationBike = id
        }
    }
    private val elevationGain = ElevationGain()
    @Volatile var movingThresholdKmh = 4f
    @Volatile var gradePointCount = DistanceAltitudeProfile.DEFAULT_GRADE_POINTS
    private var lastReliableSpeedMs = 0L

    @Synchronized
    fun resetRide() {
        track.clear(); trackPoints = emptyList(); profile.clear()
        distanceM = 0.0; altitudeTrend.reset(); altitudeSource = null
        movingTimer.reset(); elevationGain.reset(); calibration.reset()
        resetGpsSpeed()
        RideTrackJournal.startNewRide()
        _state.update { it.copy(distanceM = 0.0, track = emptyList(), profile = emptyList(),
            position = null, altitudeM = null, altitudeSource = null, altitudeUncertaintyM = null, movingTimeMs = 0, restTimeMs = 0, resting = true, pauses = emptyList(), calibration = null, elevationGainM = 0f, inclinePercent = 0f, inclineValid = false) }
        GpsDebugLog.record("RESET trajet confirmé")
    }

    @Synchronized
    fun expireGpsSpeed(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (nowMs - lastReliableSpeedMs > 4000 && _state.value.gpsSpeedValid) {
            _state.update { it.copy(gpsSpeedKmh = 0f, gpsSpeedValid = false,
                gpsStatus = "Signal GPS en attente") }
        }
        val elapsed = movingTimer.update(nowMs, _state.value.displayedSpeedKmh, movingThresholdKmh)
        _state.update { current ->
            val anchor = current.position
            val pauses = current.pauses.toMutableList()
            if (anchor != null) {
                val boundary = anchor.copy(timeMs = System.currentTimeMillis(), movingTimeMs = elapsed,
                    restTimeMs = movingTimer.restMilliseconds, resting = movingTimer.resting)
                if (movingTimer.resting && (pauses.isEmpty() || pauses.last().end != null)) {
                    pauses.add(com.alban.ebike.model.RidePause(boundary))
                } else if (!movingTimer.resting && pauses.lastOrNull()?.end == null && pauses.isNotEmpty()) {
                    pauses[pauses.lastIndex] = pauses.last().copy(end = boundary)
                }
            }
            current.copy(movingTimeMs = elapsed, restTimeMs = movingTimer.restMilliseconds,
                resting = movingTimer.resting, pauses = pauses)
        }
    }

    @Synchronized
    fun suspendTimers() { movingTimer.suspend(); calibration.disconnect() }

    @Synchronized
    fun evaluateWheelCalibration() {
        val proposal = calibration.evaluate()
        _state.update { it.copy(calibration = proposal) }
    }

    @Synchronized
    fun resetGpsSpeed() {
        measurements.reset()
        altitudeQuality.reset()
        altitudeTrend.reset()
        lastFixTimeMs = -1L
        lastReliableSpeedMs = 0
        _state.update { it.copy(gpsSpeedKmh = 0f, gpsSpeedValid = false) }
    }

    fun setBluetoothReady(ready: Boolean) {
        if (!ready) calibration.disconnect()
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
        RideTrackJournal.recordTelemetry(telemetry)
        calibration.telemetry(SystemClock.elapsedRealtime(), telemetry.uptimeMs, telemetry.wheelRevolutions)
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
        val reading = measurements.accept(fix, nowMs)
        val motion = reading.motion
        val decision = reading.track
        val approximate = reading.approximate
        lastFixTimeMs = fixTimeMs
        calibration.gps(fixTimeMs, nowMs, location.latitude, location.longitude, fix.accuracyM,
            decision.accepted && _state.value.bluetoothReady)
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
        expireGpsSpeed(nowMs)
        if (!decision.accepted) {
            val snapshot = _state.value
            RideTrackJournal.record(TrackPoint(location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude.toFloat() else 0f, location.time,
                distanceM, true, snapshot.displayedSpeedKmh, snapshot.inclinePercent.takeIf { snapshot.inclineValid },
                altitudeValid = location.hasAltitude(), movingTimeMs = snapshot.movingTimeMs,
                restTimeMs = snapshot.restTimeMs, resting = snapshot.resting, elevationGainM = snapshot.elevationGainM,
                speedSource = snapshot.displayedSpeedSource, accuracyM = fix.accuracyM,
                rawAltitudeM = location.altitude.takeIf { location.hasAltitude() },
                rawSpeedKmh = location.speed.takeIf { location.hasSpeed() }?.times(3.6f), positionValid = false,
                wheelSpeedKmh = snapshot.wheelSpeedKmh.takeIf { snapshot.bluetoothReady },
                motorSpeedKmh = snapshot.motorSpeedKmh.takeIf { snapshot.bluetoothReady }))
            return
        }
        val time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        distanceM += decision.distanceM

        val gpsHeight = com.alban.ebike.location.GpsAltitude.meters(location)
        val gpsError = com.alban.ebike.location.GpsAltitude.accuracy(location)
        val gpsAltitudeValid = altitudeQuality.accept(fixTimeMs, distanceM,
            gpsHeight, gpsError, decision.segmentStart)
        val terrainAltitude = com.alban.ebike.terrain.RideElevation.sample(elevationGrid,
            location.latitude, location.longitude, fix.accuracyM)
        val chosenAltitude = terrainAltitude ?: gpsHeight?.toFloat()?.takeIf { gpsAltitudeValid }
        val nextSource = if (terrainAltitude != null) "IGN" else if (gpsAltitudeValid) com.alban.ebike.location.GpsAltitude.source(location) else null
        val altitudeBreak = decision.segmentStart || nextSource != altitudeSource
        altitudeSource = nextSource
        val hasAltitude = chosenAltitude != null
        val uncertainty = if (!hasAltitude) null else if (nextSource == "IGN")
            com.alban.ebike.terrain.RideElevation.positionUncertainty(elevationGrid,
                location.latitude, location.longitude, fix.accuracyM)
            else gpsError
        val altitude = altitudeTrend.update(chosenAltitude, decision.distanceM, uncertainty, nextSource, altitudeBreak)
        val profilePoints = if (decision.append && !movingTimer.resting || !hasAltitude || altitudeBreak) profile.update(distanceM,
            altitude.takeIf { hasAltitude }, altitudeBreak) else _state.value.profile
        if (decision.append && !movingTimer.resting || !hasAltitude || altitudeBreak) elevationGain.update(altitude.takeIf { hasAltitude }, altitudeBreak)
        val recent = profilePoints.takeLast(gradePointCount).let { points ->
            val start = points.indexOfLast { it.segmentStart }
            if (start >= 0) points.drop(start) else points
        }
        val span = if (recent.size >= 2) recent.last().distanceM - recent.first().distanceM else 0.0
        val inclination = profile.grade(gradePointCount).takeIf {
            fix.accuracyM <= 10f && span >= maxOf(6.0, fix.accuracyM * 2.0)
        }
        GpsDebugLog.record("profile distanceM=$distanceM grade=$inclination altitude=$altitude altitudeValid=$hasAltitude source=$nextSource uncertaintyM=$uncertainty verticalAccuracy=${if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null} append=${decision.append}")
        val snapshot = _state.value
        val point = TrackPoint(location.latitude, location.longitude, altitude ?: 0f, time,
            distanceM, decision.segmentStart, snapshot.displayedSpeedKmh?.takeIf { it.isFinite() }, inclination, hasAltitude,
            movingTimeMs = movingTimer.milliseconds, restTimeMs = movingTimer.restMilliseconds, resting = movingTimer.resting, elevationGainM = elevationGain.metres,
            speedApproximate = snapshot.gpsSpeedValid && snapshot.gpsSpeedApproximate,
            speedSource = snapshot.displayedSpeedSource, accuracyM = fix.accuracyM,
            altitudeSource = nextSource, altitudeUncertaintyM = uncertainty, altitudeSegmentStart = altitudeBreak,
            rawAltitudeM = location.altitude.takeIf { location.hasAltitude() },
            rawSpeedKmh = location.speed.takeIf { location.hasSpeed() }?.times(3.6f),
            wheelSpeedKmh = snapshot.wheelSpeedKmh.takeIf { snapshot.bluetoothReady },
            motorSpeedKmh = snapshot.motorSpeedKmh.takeIf { snapshot.bluetoothReady })
        trackPoints = track.add(point)
        RideTrackJournal.record(point)
        _state.update { it.copy(
            gpsStatus = diagnostic,
            gpsSpeedKmh = motion.speedKmh,
            gpsSpeedValid = motion.reliable,
            gpsSpeedApproximate = approximate,
            elevationGainM = elevationGain.metres,
            altitudeM = altitude, altitudeSource = nextSource, altitudeUncertaintyM = uncertainty,
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
