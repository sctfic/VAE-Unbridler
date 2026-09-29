package com.alban.ebike.location

import com.alban.ebike.scene.GeoFrame
import kotlin.math.*

/** Validates positions independently of the native speed's uncertainty. */
class GpsTrackFilter {
    data class Decision(val accepted: Boolean, val append: Boolean = false,
        val distanceM: Double = 0.0, val segmentStart: Boolean = false, val reason: String = "")
    private var previous: GpsMotionFilter.Fix? = null
    private var recorded: GpsMotionFilter.Fix? = null
    private var anchor: GpsMotionFilter.Fix? = null
    private var progress: GpsMotionFilter.Fix? = null
    private var running = false
    private var broken = true
    private var confirmations = 0

    fun reset() { previous = null; anchor = null; progress = null; running = false; broken = true; confirmations = 0 }
    private fun distance(a: GpsMotionFilter.Fix, b: GpsMotionFilter.Fix): Double {
        val delta = GeoFrame.local(b.latitude, b.longitude, a.latitude, a.longitude)
        return hypot(delta.east, delta.north)
    }
    fun accept(fix: GpsMotionFilter.Fix, nowMs: Long, motion: GpsMotionFilter.Result): Decision {
        val old = previous
        if (old != null && fix.timeMs <= old.timeMs) return Decision(false, reason = "position désordonnée ignorée")
        if (nowMs - fix.timeMs !in 0L..4000L || !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..20f ||
            !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0) {
            reset(); return Decision(false, reason = "position invalide/périmée")
        }
        val dt = if (old != null) (fix.timeMs - old.timeMs) / 1000.0 else 0.0
        if (old != null && dt <= 4 && distance(old, fix) > max(35.0, dt * 25)) {
            reset(); return Decision(false, reason = "saut de position")
        }
        if (old == null || dt > 4) { broken = true; running = false; anchor = fix; progress = fix; confirmations = 0 }
        previous = fix
        confirmations++
        val wasRunning = running
        val stopped = motion.reliable && !motion.moving
        if (stopped) { running = false; anchor = fix; progress = fix; confirmations = 0 }
        else if (motion.moving) running = true
        else {
            val origin = anchor ?: fix
            // Position-only continuation/departure requires displacement larger than
            // the uncertainty within a bounded window, never long-term GPS drift.
            if (confirmations >= 3 && fix.timeMs - origin.timeMs >= 1500 &&
                distance(origin, fix) > max(6.0, (origin.accuracyM + fix.accuracyM) * 1.5)) running = true
            if (fix.timeMs - origin.timeMs > 8000) { anchor = fix; confirmations = 0 }
        }
        val lastProgress = progress ?: fix
        if (distance(lastProgress, fix) > max(3.0, (lastProgress.accuracyM + fix.accuracyM).toDouble())) progress = fix
        if (!motion.moving && !stopped && fix.timeMs - (progress ?: fix).timeMs > 5000) running = false
        if (recorded == null || running || wasRunning && stopped || broken) {
            val delta = if (!broken && recorded != null) distance(recorded!!, fix) else 0.0
            val first = broken
            recorded = fix; broken = false
            return Decision(true, true, if (delta >= .4) delta else 0.0, first,
                if (first) "nouveau segment" else "trace continue")
        }
        return Decision(true, reason = "position stationnaire")
    }
}
