package com.alban.ebike.location

import kotlin.math.*

/** Conservative motion confirmation; never derives speed from position jitter. */
class GpsMotionFilter {
    data class Fix(val timeMs: Long, val latitude: Double, val longitude: Double,
        val accuracyM: Float, val speedMps: Float?, val speedAccuracyMps: Float?)
    data class Result(val speedKmh: Float, val reliable: Boolean, val moving: Boolean,
        val reason: String = "")

    private var anchor: Fix? = null
    private var previousTime = -1L
    private var confirmations = 0
    private var moving = false
    private var lastTrustedMs = -1L
    private var filteredSpeed = 0f
    private val speeds = ArrayDeque<Float>()

    fun reset() {
        anchor = null
        previousTime = -1L
        confirmations = 0
        moving = false
        lastTrustedMs = -1L
        filteredSpeed = 0f
        speeds.clear()
    }

    fun accept(fix: Fix, nowMs: Long): Result {
        // Late/duplicate callbacks must not invalidate a more recent accepted fix.
        if (fix.timeMs <= previousTime) return Result(0f, false, moving, "fix désordonné")
        if (nowMs - fix.timeMs !in 0L..4000L || fix.timeMs <= previousTime ||
            !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..20f ||
            !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0) {
            reset()
            return Result(0f, false, false, when {
                nowMs - fix.timeMs !in 0L..4000L -> "fix périmé"
                !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..20f -> "précision >20m/inconnue"
                else -> "fix invalide/désordonné"
            })
        }
        if (previousTime >= 0 && fix.timeMs - previousTime > 4000) reset()
        val dt = if (previousTime >= 0) (fix.timeMs - previousTime) / 1000f else 1f
        previousTime = fix.timeMs
        val speed = fix.speedMps
        val error = fix.speedAccuracyMps
        if (speed == null || error == null || !speed.isFinite() || !error.isFinite() ||
            speed !in 0f..33.333f || error < 0f ||
            error > (if (moving) max(1f, speed * .30f).coerceAtMost(3f) else 1f)) {
            if (!moving) { anchor = fix; confirmations = 0 }
            return uncertain(fix, when {
                speed == null -> "vitesse absente"
                error == null -> "incertitude vitesse absente"
                error > 1f -> "vitesse peu précise"
                else -> "vitesse invalide"
            })
        }
        // A slow or uncertain measurement is not evidence of a stop.
        // Only an explicitly low native speed confirms zero.
        val low = speed - 2 * error < if (moving) .15f else .2f
        if (speed <= .5f && error <= 1f) {
            anchor = fix
            confirmations = 0
            moving = false
            filteredSpeed = 0f
            speeds.clear()
            lastTrustedMs = fix.timeMs
            return Result(0f, true, false, "arrêt/seuil bas")
        }
        if (low) return uncertain(fix, "mouvement incertain")
        if (anchor == null) anchor = fix
        confirmations++
        val origin = anchor!!
        val displacement = distanceM(origin, fix)
        // A precise native speed can establish movement without waiting for a large position baseline.
        if (!moving && confirmations >= 2 && fix.timeMs - origin.timeMs >= 500 &&
            fix.accuracyM <= 10f && error <= .5f && speed >= .8f && displacement > 3.0) moving = true
        if (!moving && confirmations >= 3 && fix.timeMs - origin.timeMs >= 1500 &&
            displacement > max(3.0, (origin.accuracyM + fix.accuracyM).toDouble())) moving = true
        if (!moving) return Result(0f, false, false, "confirmation $confirmations/3 · déplacement ${displacement.toInt()}m")
        // Three-point median removes isolated spikes; time/accuracy-weighted EMA
        // provides a short smoothing horizon without delaying a confirmed stop.
        if (lastTrustedMs < 0 || fix.timeMs - lastTrustedMs > 3000) speeds.clear()
        speeds.addLast(speed)
        while (speeds.size > 3) speeds.removeFirst()
        val sorted = speeds.sorted()
        val median = if (sorted.size == 2) (sorted[0] + sorted[1]) / 2 else sorted[sorted.size / 2]
        val tau = 0.7f + error.coerceAtMost(2f) * .35f
        val alpha = (1 - exp(-dt / tau)).coerceIn(0f, 1f)
        filteredSpeed = if (speeds.size == 1) median else filteredSpeed + alpha * (median - filteredSpeed)
        lastTrustedMs = fix.timeMs
        return Result(filteredSpeed * 3.6f, true, true, "vitesse lissée · mouvement confirmé")
    }

    private fun uncertain(fix: Fix, reason: String): Result {
        val age = fix.timeMs - lastTrustedMs
        if (moving && lastTrustedMs >= 0 && age in 0..2000)
            return Result(filteredSpeed * 3.6f, true, true, "maintien ${age / 1000}s · $reason")
        // Keep movement memory briefly, but never display a stale speed indefinitely.
        if (age > 6000) { moving = false; confirmations = 0; speeds.clear(); anchor = fix }
        return Result(0f, false, moving, reason)
    }

    private fun distanceM(a: Fix, b: Fix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
