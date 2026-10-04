package com.alban.ebike.location

/** Resolve speed once, then feed that same decision to distance and the UI/timers. */
class GpsMeasurements {
    data class Reading(val motion: GpsMotionFilter.Result, val track: GpsTrackFilter.Decision, val approximate: Boolean)
    private val native = GpsMotionFilter()
    private val regression = PositionSpeedRegression()
    private val track = GpsTrackFilter()
    fun reset() { native.reset(); regression.reset(); track.reset() }
    fun accept(fix: GpsMotionFilter.Fix, nowMs: Long): Reading {
        var motion = native.accept(fix, nowMs)
        val estimated = regression.accept(fix, nowMs)
        var approximate = (!motion.reliable || motion.reason.startsWith("maintien")) && estimated != null
        if (approximate) motion = GpsMotionFilter.Result(estimated!!, true, estimated > 1.8f,
            "régression coordonnées · approximation")
        val decision = track.accept(fix, nowMs, motion)
        if (!decision.accepted) {
            native.reset(); regression.reset()
            approximate = false
            motion = GpsMotionFilter.Result(0f, false, false, decision.reason)
        }
        return Reading(motion, decision, approximate)
    }
}
