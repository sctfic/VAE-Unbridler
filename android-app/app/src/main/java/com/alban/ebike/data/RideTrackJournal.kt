package com.alban.ebike.data

import android.content.Context
import android.util.Log
import com.alban.ebike.model.TrackPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/** All GPS samples and BLE packets are archived, including rests and rejected fixes. */
object RideTrackJournal {
    private data class TelemetrySample(val timeMs: Long, val bikeId: String, val value: com.alban.ebike.model.BikeTelemetry)
    private val points = Channel<Any?>(4096)
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val directory = File(context.filesDir, "rides")
        var file = File(directory, "ride-${System.currentTimeMillis()}.csv")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (event in points) {
                if (event == null) {
                    file = File(directory, "ride-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.csv")
                    continue
                }
                runCatching {
                    check(directory.isDirectory || directory.mkdirs())
                    if (event is TelemetrySample) {
                        val telemetryFile = File(directory, file.nameWithoutExtension + "-telemetry.csv")
                        if (!telemetryFile.exists()) telemetryFile.appendText("time_ms,bike_id,sequence,uptime_ms,wheel_ticks,emitted_pulses,wheel_interval_us,motor_interval_us,wheel_speed_kmh,motor_speed_kmh,circumference_mm,speed_mode,mode_supported,wheel_moving,simulated_output\n")
                        val t = event.value
                        telemetryFile.appendText("${event.timeMs},${event.bikeId},${t.sequence},${t.uptimeMs},${t.wheelRevolutions},${t.emittedPulses},${t.wheelIntervalUs},${t.motorIntervalUs},${t.wheelSpeedKmh},${t.motorSpeedKmh},${t.circumferenceMm},${t.speedMode},${t.modeSupported},${t.wheelMoving},${t.simulatedOutput}\n")
                        return@runCatching
                    }
                    val point = event as TrackPoint
                    if (!file.exists()) file.appendText("time_ms,latitude,longitude,altitude_m,distance_m,segment_start,speed_kmh,grade_percent,altitude_valid,moving_time_ms,elevation_gain_m,speed_approximate,speed_source,rest_time_ms,resting,accuracy_m,raw_altitude_m,raw_speed_kmh,position_valid,wheel_speed_kmh,motor_speed_kmh,altitude_source,altitude_segment_start,altitude_uncertainty_m\n")
                    file.appendText("${point.timeMs},${point.latitude},${point.longitude},${point.altitudeM},${point.distanceM},${point.segmentStart},${point.speedKmh ?: ""},${point.gradePercent ?: ""},${point.altitudeValid},${point.movingTimeMs ?: ""},${point.elevationGainM ?: ""},${point.speedApproximate},${point.speedSource ?: ""},${point.restTimeMs ?: ""},${point.resting},${point.accuracyM ?: ""},${point.rawAltitudeM ?: ""},${point.rawSpeedKmh ?: ""},${point.positionValid},${point.wheelSpeedKmh ?: ""},${point.motorSpeedKmh ?: ""},${point.altitudeSource ?: ""},${point.altitudeSegmentStart},${point.altitudeUncertaintyM ?: ""}\n")
                }.onFailure { Log.e("EBikeTrack", "Unable to persist ride point", it) }
            }
        }
    }
    fun record(point: TrackPoint) {
        if (initialized && points.trySend(point).isFailure) Log.e("EBikeTrack", "Ride journal queue full; point not saved")
    }
    fun recordTelemetry(value: com.alban.ebike.model.BikeTelemetry) {
        if (initialized && points.trySend(TelemetrySample(System.currentTimeMillis(), BikeProfiles.activeId.value, value)).isFailure)
            Log.e("EBikeTrack", "Ride journal queue full; telemetry not saved")
    }
    fun startNewRide() {
        if (initialized && points.trySend(null).isFailure) Log.e("EBikeTrack", "Ride journal reset queue full")
    }
}
