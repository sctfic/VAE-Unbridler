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

    fun reset() {
        anchor = null
        previousTime = -1L
        confirmations = 0
        moving = false
    }

    fun accept(fix: Fix, nowMs: Long): Result {
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
        previousTime = fix.timeMs
        val speed = fix.speedMps
        val error = fix.speedAccuracyMps
        if (speed == null || error == null || !speed.isFinite() || !error.isFinite() ||
            speed !in 0f..33.333f || error !in 0f..1f) {
            anchor = fix
            confirmations = 0
            moving = false
            return Result(0f, false, false, when {
                speed == null -> "vitesse absente"
                error == null -> "incertitude vitesse absente"
                error > 1f -> "incertitude vitesse >1m/s"
                else -> "vitesse invalide"
            })
        }
        // Require the lower confidence bound to exceed 0.7 m/s (~2.5 km/h).
        if (speed - 2 * error < 0.7f) {
            anchor = fix
            confirmations = 0
            moving = false
            return Result(0f, true, false, "arrêt/seuil bas")
        }
        if (anchor == null) anchor = fix
        confirmations++
        val origin = anchor!!
        val displacement = distanceM(origin, fix)
        if (!moving && confirmations >= 3 && fix.timeMs - origin.timeMs >= 1500 &&
            displacement > max(3.0, (origin.accuracyM + fix.accuracyM).toDouble())) moving = true
        return if (moving) Result(speed * 3.6f, true, true, "mouvement confirmé")
            else Result(0f, false, false, "confirmation $confirmations/3 · déplacement ${displacement.toInt()}m")
    }

    private fun distanceM(a: Fix, b: Fix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
