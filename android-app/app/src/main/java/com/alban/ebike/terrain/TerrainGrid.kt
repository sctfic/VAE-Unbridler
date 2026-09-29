package com.alban.ebike.terrain

import kotlin.math.*

data class TileKey(val zoom: Int, val x: Int, val y: Int) {
    val fileName get() = "$zoom-$x-$y.png"
}

object TerrainCoordinates {
    fun tile(latitude: Double, longitude: Double, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val lat = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        val lon = ((longitude + 180) % 360 + 360) % 360 - 180
        return ((lon + 180) / 360 * n) to ((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n).coerceIn(0.0, n - 1e-8)
    }
    fun elevation(argb: Int): Float =
        ((argb shr 16 and 255) * 256 + (argb shr 8 and 255) + (argb and 255) / 256f) - 32768
}

data class TerrainGrid(val originLat: Double, val originLon: Double, val halfSizeM: Double,
    val size: Int, val heights: FloatArray, val status: String, val real: Boolean) {
    fun sample(east: Double, north: Double): Double? {
        val x = (east / halfSizeM + 1) * .5 * (size - 1)
        val y = (north / halfSizeM + 1) * .5 * (size - 1)
        if (x !in 0.0..(size - 1).toDouble() || y !in 0.0..(size - 1).toDouble()) return null
        val ix = floor(x).toInt().coerceAtMost(size - 2)
        val iy = floor(y).toInt().coerceAtMost(size - 2)
        val dx = x - ix; val dy = y - iy
        val a = heights[iy * size + ix]; val b = heights[iy * size + ix + 1]
        val c = heights[(iy + 1) * size + ix]; val d = heights[(iy + 1) * size + ix + 1]
        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) return null
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy
    }

    /** Bicubic display interpolation. Values remain bounded by their 4×4 source neighbourhood. */
    fun sampleSmooth(east: Double, north: Double): Double? {
        val x = (east / halfSizeM + 1) * .5 * (size - 1)
        val y = (north / halfSizeM + 1) * .5 * (size - 1)
        if (x !in 1.0..(size - 3).toDouble() || y !in 1.0..(size - 3).toDouble()) return sample(east, north)
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val values = Array(4) { row -> FloatArray(4) { col -> heights[(iy + row - 1) * size + ix + col - 1] } }
        if (values.any { row -> row.any { !it.isFinite() } }) return sample(east, north)
        fun cubic(a: Double, b: Double, c: Double, d: Double, t: Double): Double =
            b + .5 * t * (c - a + t * (2 * a - 5 * b + 4 * c - d + t * (3 * (b - c) + d - a)))
        val dx = x - ix; val dy = y - iy
        val rows = DoubleArray(4) { row -> cubic(values[row][0].toDouble(), values[row][1].toDouble(),
            values[row][2].toDouble(), values[row][3].toDouble(), dx) }
        val result = cubic(rows[0], rows[1], rows[2], rows[3], dy)
        val low = values.minOf { it.min() }.toDouble(); val high = values.maxOf { it.max() }.toDouble()
        return result.coerceIn(low, high)
    }
}
