package com.alban.ebike.data

import kotlin.math.abs
import kotlin.math.exp

/** Spatial smoothing with a reversal threshold tied to altitude uncertainty, not a forced uphill route. */
class AltitudeTrendFilter {
    private var smoothed: Float? = null
    private var displayed: Float? = null
    private var direction = 0

    fun reset() { smoothed = null; displayed = null; direction = 0 }

    fun update(raw: Float?, stepM: Double, uncertaintyM: Float?, source: String?, boundary: Boolean): Float? {
        if (boundary || raw == null || !raw.isFinite()) reset()
        if (raw == null || !raw.isFinite()) return null
        val old = displayed
        if (old == null) { smoothed = raw; displayed = raw; return raw }
        if (stepM <= 0) return old // Never turn stationary altitude drift into elevation gain.
        val horizon = if (source?.startsWith("GPS") == true) 20.0 else 8.0
        val alpha = (1 - exp(-stepM / horizon)).toFloat().coerceIn(0f, 1f)
        val next = smoothed!! + alpha * (raw - smoothed!!)
        smoothed = next
        val delta = next - old
        val sign = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val reversal = maxOf(.4f, (uncertaintyM ?: 1f) * .75f)
        if (direction != 0 && sign != 0 && sign != direction && abs(delta) < reversal) return old
        if (abs(delta) > .02f) direction = sign
        displayed = next
        return next
    }
}
