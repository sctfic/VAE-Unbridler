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

    fun grade(sampleCount: Int = DEFAULT_GRADE_POINTS): Float? =
        if (pendingBreak) null else terminalGrade(points.toList(), sampleCount)

    companion object {
        const val WINDOW_M = 500.0
        const val DEFAULT_GRADE_POINTS = 10
        fun horizontalFraction(pointDistanceM: Double, currentDistanceM: Double, windowM: Double = WINDOW_M): Float =
            ((pointDistanceM - currentDistanceM + windowM) / windowM.coerceAtLeast(1.0)).toFloat()

        fun visible(points: List<AltitudePoint>, currentDistanceM: Double, windowM: Double): List<AltitudePoint> {
            val first = points.indexOfFirst { it.distanceM >= currentDistanceM - windowM }
            return if (first < 0) emptyList() else points.drop((first - 1).coerceAtLeast(0))
        }

        /** Last N accepted moving GPS samples, with no fixed distance window. */
        fun terminalGrade(points: List<AltitudePoint>, sampleCount: Int = DEFAULT_GRADE_POINTS): Float? {
            require(sampleCount in 3..30)
            val recent = ArrayList<AltitudePoint>(sampleCount)
            for (index in points.indices.reversed()) {
                val point = points[index]
                if (!point.distanceM.isFinite() || !point.altitudeM.isFinite()) break
                if (recent.isEmpty() || point.distanceM < recent.last().distanceM) recent.add(point)
                if (point.segmentStart || recent.size == sampleCount) break
            }
            if (recent.size < 3) return null
            // Offset distances to retain precision after long rides; no minimum span in metres.
            val origin = recent.last().distanceM
            val meanX = recent.map { it.distanceM - origin }.average()
            val meanY = recent.map { it.altitudeM.toDouble() }.average()
            val variance = recent.sumOf { val x = it.distanceM - origin - meanX; x * x }
            if (variance <= 1e-9) return null
            return (100 * recent.sumOf { (it.distanceM - origin - meanX) * (it.altitudeM - meanY) } / variance).toFloat()
        }
    }
}
