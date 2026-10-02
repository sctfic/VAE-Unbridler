package com.alban.ebike.location

import kotlin.math.*

/** Weighted least squares in a local metric frame, over at most six seconds. */
class PositionSpeedRegression {
    private val fixes = ArrayDeque<GpsMotionFilter.Fix>()
    fun reset() = fixes.clear()
    fun accept(fix: GpsMotionFilter.Fix, nowMs: Long): Float? {
        if (nowMs - fix.timeMs !in 0L..4000L || !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..20f ||
            !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0) { reset(); return null }
        val previous = fixes.lastOrNull()
        if (previous != null && fix.timeMs <= previous.timeMs) return null
        if (previous != null && fix.timeMs - previous.timeMs > 4000) reset()
        fixes.addLast(fix)
        while (fixes.size > 32 || fix.timeMs - fixes.first().timeMs > 6000) fixes.removeFirst()
        if (fixes.size < 3 || fix.timeMs - fixes.first().timeMs < 2000) return null
        val origin = fixes.first()
        val samples = fixes.map {
            val longitudeDelta = ((it.longitude - origin.longitude + 540) % 360) - 180
            doubleArrayOf((it.timeMs - origin.timeMs) / 1000.0,
                Math.toRadians(longitudeDelta) * 6371000 * cos(Math.toRadians(origin.latitude)),
                Math.toRadians(it.latitude - origin.latitude) * 6371000,
                1.0 / it.accuracyM.coerceAtLeast(3f).pow(2))
        }
        val weight = samples.sumOf { it[3] }
        val means = (0..2).map { axis -> samples.sumOf { it[axis] * it[3] } / weight }
        val variance = samples.sumOf { it[3] * (it[0] - means[0]).pow(2) }
        if (variance < .001) return null
        fun slope(axis: Int) = samples.sumOf { it[3] * (it[0] - means[0]) * (it[axis] - means[axis]) } / variance
        val east = slope(1); val north = slope(2)
        val speed = hypot(east, north)
        val residual = sqrt(samples.sumOf {
            it[3] * ((it[1] - means[1] - east * (it[0] - means[0])).pow(2) +
                (it[2] - means[2] - north * (it[0] - means[0])).pow(2))
        } / weight)
        if (speed > 33.333 || residual > max(8.0, fixes.map { it.accuracyM }.average())) return null
        // Suppress sub-resolution drift without claiming a measured stop.
        val span = (fix.timeMs - origin.timeMs) / 1000.0
        return if (speed * span < max(3.0, residual * 2)) 0f else (speed * 3.6).toFloat()
    }
}
