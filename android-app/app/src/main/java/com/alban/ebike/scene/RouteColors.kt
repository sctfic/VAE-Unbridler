package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint

enum class RouteMetric(val label: String) { SPEED("VITESSE"), ALTITUDE("ALTITUDE"), GRADE("PENTE") }

object RouteColors {
    fun range(points: List<TrackPoint>, metric: RouteMetric): Pair<Float, Float> = when (metric) {
        RouteMetric.SPEED -> 0f to 50f
        RouteMetric.GRADE -> -15f to 15f
        RouteMetric.ALTITUDE -> {
            val heights = points.filter { it.altitudeValid }.map { it.altitudeM }.filter { it.isFinite() }
            val low = heights.minOrNull() ?: 0f
            low to (heights.maxOrNull() ?: low).coerceAtLeast(low + 1f)
        }
    }
    fun color(point: TrackPoint, metric: RouteMetric, range: Pair<Float, Float>): FloatArray {
        val value = when (metric) {
            RouteMetric.SPEED -> point.speedKmh
            RouteMetric.GRADE -> point.gradePercent
            RouteMetric.ALTITUDE -> point.altitudeM.takeIf { point.altitudeValid }
        }
        if (value == null || !value.isFinite()) return floatArrayOf(.45f, .50f, .55f)
        return palette(((value - range.first) / (range.second - range.first)).coerceIn(0f, 1f))
    }
    fun palette(t: Float): FloatArray {
        val stops = arrayOf(floatArrayOf(.15f, .55f, 1f), floatArrayOf(.1f, .95f, .65f),
            floatArrayOf(1f, .8f, .15f), floatArrayOf(1f, .15f, .25f))
        val x = t.coerceIn(0f, 1f) * 3
        val i = x.toInt().coerceAtMost(2)
        return FloatArray(3) { stops[i][it] + (stops[i + 1][it] - stops[i][it]) * (x - i) }
    }
}
