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

        /** Regression of spatially resampled heights over 20 m, never across gaps. */
        fun terminalGrade(points: List<AltitudePoint>): Float? {
            val end = points.lastOrNull() ?: return null
            if (end.segmentStart) return null
            val segment = points.takeLastWhile { !it.segmentStart }.let { tail ->
                points.getOrNull(points.size - tail.size - 1)?.let { listOf(it) + tail } ?: tail
            }
            val start = maxOf(end.distanceM - 20.0, segment.first().distanceM)
            val span = end.distanceM - start
            if (span < 12) return null
            val samples = (0..10).map { i ->
                val x = start + span * i / 10
                val right = segment.indexOfFirst { it.distanceM >= x }.coerceAtLeast(0)
                val b = segment[right]; val a = segment[(right - 1).coerceAtLeast(0)]
                val fraction = if (b.distanceM > a.distanceM) (x - a.distanceM) / (b.distanceM - a.distanceM) else 0.0
                x to a.altitudeM + fraction * (b.altitudeM - a.altitudeM)
            }
            val meanX = samples.map { it.first }.average(); val meanY = samples.map { it.second }.average()
            val variance = samples.sumOf { (it.first - meanX) * (it.first - meanX) }
            return (100 * samples.sumOf { (it.first - meanX) * (it.second - meanY) } / variance).toFloat()
        }
    }
}
