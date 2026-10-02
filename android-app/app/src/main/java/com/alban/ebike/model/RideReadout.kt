package com.alban.ebike.model

/** Display-only snapshot: browsing a point never changes the live ride or map acquisition. */
data class RideReadout(
    val speedKmh: Float?,
    val speedSource: String,
    val speedApproximate: Boolean,
    val altitudeM: Float?,
    val gradePercent: Float?,
    val distanceM: Double,
    val elevationGainM: Float?,
    val movingTimeMs: Long?,
    val historical: Boolean,
    val restTimeMs: Long? = null,
    val resting: Boolean = false,
) {
    companion object {
        fun from(state: RideUiState, selected: TrackPoint? = null): RideReadout = if (selected == null) {
            RideReadout(state.displayedSpeedKmh, state.displayedSpeedSource,
                state.gpsSpeedValid && state.gpsSpeedApproximate, state.altitudeM,
                state.inclinePercent.takeIf { state.inclineValid }, state.distanceM,
                state.elevationGainM, state.movingTimeMs, false, state.restTimeMs, state.resting)
        } else {
            RideReadout(selected.speedKmh, selected.speedSource ?: "GPS", selected.speedApproximate,
                selected.altitudeM.takeIf { selected.altitudeValid }, selected.gradePercent,
                selected.distanceM, selected.elevationGainM, selected.movingTimeMs, true, selected.restTimeMs, selected.resting)
        }
    }
}

fun formatRideDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}
