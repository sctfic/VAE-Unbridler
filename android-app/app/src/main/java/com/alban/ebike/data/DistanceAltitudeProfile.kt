package com.alban.ebike.data

import com.alban.ebike.model.AltitudePoint

/** Fixed spatial scale, independent of GPS sampling rate or time spent stopped. */
class DistanceAltitudeProfile {
    private val points = ArrayDeque<AltitudePoint>()
    private var pendingBreak = false
    fun clear() { points.clear(); pendingBreak = false }

    fun update(distanceM: Double, altitudeM: Float?, segmentStart: Boolean = false): List<AltitudePoint> {
        require(distanceM.isFinite() && distanceM >= 0.0)
        val last = points.lastOrNull()
        pendingBreak = pendingBreak || segmentStart || altitudeM == null || !altitudeM.isFinite()
        if (altitudeM != null && altitudeM.isFinite() &&
            (last == null || distanceM - last.distanceM >= 0.4 || segmentStart)) {
            points.addLast(AltitudePoint(distanceM, altitudeM, pendingBreak || last == null))
            pendingBreak = false
        }
        return points.toList()
    }

    fun grade(): Float? = if (pendingBreak) null else terminalGrade(points.toList())

    companion object {
        const val WINDOW_M = 500.0
        fun horizontalFraction(pointDistanceM: Double, currentDistanceM: Double, windowM: Double = WINDOW_M): Float =
            ((pointDistanceM - currentDistanceM + windowM) / windowM.coerceAtLeast(1.0)).toFloat()

        fun visible(points: List<AltitudePoint>, currentDistanceM: Double, windowM: Double): List<AltitudePoint> {
            val first = points.indexOfFirst { it.distanceM >= currentDistanceM - windowM }
            return if (first < 0) emptyList() else points.drop((first - 1).coerceAtLeast(0))
        }

        /** Secant of the actual profile over its last 20 m; never across a GPS/altitude gap. */
        fun terminalGrade(points: List<AltitudePoint>): Float? {
            val end = points.lastOrNull() ?: return null
            if (end.segmentStart) return null
            var reference = end
            val target = end.distanceM - 20.0
            for (i in points.size - 2 downTo 0) {
                val p = points[i]
                if (p.distanceM <= target && reference.distanceM > p.distanceM) {
                    val fraction = (target - p.distanceM) / (reference.distanceM - p.distanceM)
                    val height = p.altitudeM + fraction * (reference.altitudeM - p.altitudeM)
                    return ((end.altitudeM - height) * 5).toFloat()
                }
                reference = p
                if (p.segmentStart) break
            }
            val span = end.distanceM - reference.distanceM
            return if (span >= 12) ((end.altitudeM - reference.altitudeM) / span * 100).toFloat() else null
        }
    }
}
