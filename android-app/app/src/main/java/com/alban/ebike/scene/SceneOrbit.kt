package com.alban.ebike.scene

import kotlin.math.*

/** Render-thread camera state. Times are monotonic milliseconds. */
class SceneOrbit {
    var heading = 0.0
        private set
    var tilt = TrackCamera.TILT
        private set
    var zoom = 1.0
        private set
    private var touching = false
    private var releasedAt = Long.MIN_VALUE
    private var frameAt = -1L
    private var progressDistance = 0.0
    private var progressAt = -1L

    fun touch() { touching = true }
    fun drag(radians: Double) { heading = wrap(heading + radians) }
    fun incline(radians: Double) { tilt = (tilt + radians).coerceIn(Math.toRadians(20.0), Math.toRadians(80.0)) }
    fun scale(factor: Double) { if (factor.isFinite() && factor > 0) zoom = (zoom * factor).coerceIn(.5, 4.0) }
    fun release(now: Long) { touching = false; releasedAt = now }
    fun movement(distance: Double, now: Long) {
        if (distance < progressDistance) { progressDistance = distance; progressAt = -1L }
        if (distance - progressDistance >= 3.0) { progressDistance = distance; progressAt = now }
    }
    fun advance(automaticHeading: Double, now: Long, grade: Float = 0f): Double {
        val dt = if (frameAt < 0) 0.0 else ((now - frameAt) / 1000.0).coerceIn(0.0, .1)
        frameAt = now
        if (touching || releasedAt != Long.MIN_VALUE && now - releasedAt < 3000) return heading
        val response = 1 - exp(-dt / .7)
        val baseTilt = if (progressAt >= 0 && now - progressAt < 5000) Math.toRadians(38.0) else TrackCamera.TILT
        val targetTilt = (baseTilt - atan(grade.toDouble() / 100) * 1.5)
            .coerceIn(Math.toRadians(30.0), Math.toRadians(70.0))
        tilt += (targetTilt - tilt) * response
        zoom += (1.0 - zoom) * response
        if (progressAt < 0 || now - progressAt >= 5000) heading = wrap(heading + dt * Math.toRadians(4.0))
        else heading = wrap(heading + GeoFrame.angleDelta(heading, automaticHeading) * (1 - exp(-dt / .5)))
        return heading
    }
    private fun wrap(angle: Double) = atan2(sin(angle), cos(angle))
}
