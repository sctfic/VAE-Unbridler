package com.alban.ebike.data

import android.content.Context
import android.util.Log
import com.alban.ebike.model.TrackPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/** Accepted ride points are archived; reset starts a new file without deleting history. */
object RideTrackJournal {
    private val points = Channel<TrackPoint?>(4096)
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val directory = File(context.filesDir, "rides")
        var file = File(directory, "ride-${System.currentTimeMillis()}.csv")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (point in points) {
                if (point == null) {
                    file = File(directory, "ride-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.csv")
                    continue
                }
                runCatching {
                    check(directory.isDirectory || directory.mkdirs())
                    if (!file.exists()) file.appendText("time_ms,latitude,longitude,gps_altitude_m,distance_m,segment_start,speed_kmh,grade_percent,altitude_valid,moving_time_ms,elevation_gain_m,speed_approximate,speed_source,rest_time_ms,resting\n")
                    file.appendText("${point.timeMs},${point.latitude},${point.longitude},${point.altitudeM},${point.distanceM},${point.segmentStart},${point.speedKmh ?: ""},${point.gradePercent ?: ""},${point.altitudeValid},${point.movingTimeMs ?: ""},${point.elevationGainM ?: ""},${point.speedApproximate},${point.speedSource ?: ""},${point.restTimeMs ?: ""},${point.resting}\n")
                }.onFailure { Log.e("EBikeTrack", "Unable to persist ride point", it) }
            }
        }
    }
    fun record(point: TrackPoint) {
        if (initialized && points.trySend(point).isFailure) Log.e("EBikeTrack", "Ride journal queue full; point not saved")
    }
    fun startNewRide() {
        if (initialized && points.trySend(null).isFailure) Log.e("EBikeTrack", "Ride journal reset queue full")
    }
}
