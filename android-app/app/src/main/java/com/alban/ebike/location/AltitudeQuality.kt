package com.alban.ebike.location

import kotlin.math.abs

/** Conservative quality gate; preserves raw samples elsewhere and never invents an altitude. */
class AltitudeQuality {
    private var previous: Double? = null
    private var time = -1L
    private var unchangedSince = -1L
    private var unchangedDistance = 0.0
    private var confirmations = 0
    fun reset() { previous = null; time = -1; unchangedSince = -1; confirmations = 0 }
    fun accept(now: Long, distance: Double, altitude: Double?, accuracy: Float?, segmentStart: Boolean): Boolean {
        if (segmentStart || time >= 0 && now - time > 4000) reset()
        val old = previous
        val oldTime = time
        time = now
        if (altitude == null || !altitude.isFinite() || accuracy == null || !accuracy.isFinite() || accuracy !in 0f..15f) {
            previous = null; confirmations = 0; unchangedSince = -1
            return false
        }
        if (old == null || altitude != old) { unchangedSince = now; unchangedDistance = distance }
        previous = altitude
        // Exact binary repetition across sustained travel suggests stale fused altitude, not merely a flat road.
        if (now - unchangedSince >= 20000 && distance - unchangedDistance >= 30) {
            confirmations = 0
            return false
        }
        val seconds = ((now - oldTime) / 1000.0).coerceAtLeast(.5)
        if (old != null && abs(altitude - old) > maxOf(6.0, seconds * 3.0)) {
            confirmations = 0
            return false
        }
        confirmations++
        return confirmations >= 3
    }
}
