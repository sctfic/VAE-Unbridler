package com.alban.ebike.data

/** Exactly one timer owns every elapsed interval; only a valid above-threshold speed means movement. */
class MovingTimer {
    var milliseconds = 0L; private set
    var restMilliseconds = 0L; private set
    var resting = true; private set
    private var previousMs: Long? = null
    fun reset() { milliseconds = 0; restMilliseconds = 0; resting = true; previousMs = null }
    fun suspend() { previousMs = null }
    fun update(now: Long, speed: Float?, threshold: Float): Long {
        val moving = speed != null && speed.isFinite() && speed > threshold
        previousMs?.let {
            val elapsed = (now - it).coerceAtLeast(0)
            if (resting) restMilliseconds += elapsed else milliseconds += elapsed
        }
        if (moving) restMilliseconds = 0
        previousMs = now
        resting = !moving
        return milliseconds
    }
}

/** Three metre reversal hysteresis avoids summing every positive GPS fluctuation. */
class ElevationGain {
    var metres = 0f; private set
    private var base: Float? = null
    private var peak = 0f
    private var climbing = false
    fun reset() { metres = 0f; base = null; climbing = false }
    fun update(height: Float?, segmentStart: Boolean): Float {
        if (height == null || !height.isFinite()) { base = null; climbing = false; return metres }
        if (base == null || segmentStart) { base = height; peak = height; climbing = false; return metres }
        if (!climbing) {
            base = minOf(base!!, height)
            if (height - base!! >= 3f) { metres += height - base!!; peak = height; climbing = true }
        } else if (height > peak) { metres += height - peak; peak = height }
        else if (peak - height >= 3f) { base = height; climbing = false }
        return metres
    }
}
