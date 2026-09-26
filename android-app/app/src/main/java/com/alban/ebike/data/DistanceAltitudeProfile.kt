package com.alban.ebike.data

import com.alban.ebike.model.AltitudePoint

/** Fixed spatial scale, independent of GPS sampling rate or time spent stopped. */
class DistanceAltitudeProfile {
    private val points = ArrayDeque<AltitudePoint>()

    fun update(distanceM: Double, altitudeM: Float?): List<AltitudePoint> {
        require(distanceM.isFinite() && distanceM >= 0.0)
        val last = points.lastOrNull()
        if (altitudeM != null && altitudeM.isFinite() &&
            (last == null || distanceM - last.distanceM >= 0.4)) {
            points.addLast(AltitudePoint(distanceM, altitudeM))
        }
        // Retain one point outside the window to interpolate the left edge.
        val start = distanceM - WINDOW_M
        while (points.size > 1 && points[1].distanceM <= start) points.removeFirst()
        return points.toList()
    }

    companion object {
        const val WINDOW_M = 500.0
        fun horizontalFraction(pointDistanceM: Double, currentDistanceM: Double): Float =
            ((pointDistanceM - currentDistanceM + WINDOW_M) / WINDOW_M).toFloat()
    }
}
