package com.alban.ebike.terrain

import kotlin.math.floor
import kotlin.math.min

/** Share the exact rendered triangle surface with the draped overlays. */
object TerrainSurface {
    fun size(grid: TerrainGrid?, detail: TerrainDetail) = if (grid == null) 65 else
        min(detail.renderSize, if (grid.size >= 97) grid.size * 2 - 1 else 129)

    private var cachedGrid: TerrainGrid? = null
    private var cachedDetail: TerrainDetail? = null
    private var cachedSampler: Sampler? = null
    @Synchronized fun sampler(grid: TerrainGrid, detail: TerrainDetail): Sampler {
        if (cachedGrid === grid && cachedDetail == detail) return cachedSampler!!
        return Sampler(grid, detail).also { cachedGrid = grid; cachedDetail = detail; cachedSampler = it }
    }
    fun sample(grid: TerrainGrid, detail: TerrainDetail, east: Double, north: Double) = sampler(grid, detail).sample(east, north)

    class Sampler(private val grid: TerrainGrid, detail: TerrainDetail) {
        private val n = size(grid, detail)
        private val heights = DoubleArray(n * n) { i ->
            grid.sampleSmooth((i % n / (n - 1.0) * 2 - 1) * grid.halfSizeM,
                (i / n / (n - 1.0) * 2 - 1) * grid.halfSizeM) ?: Double.NaN
        }
        fun sample(east: Double, north: Double): Double? {
            val x = (east / grid.halfSizeM + 1) * .5 * (n - 1)
            val y = (north / grid.halfSizeM + 1) * .5 * (n - 1)
            if (x !in 0.0..(n - 1).toDouble() || y !in 0.0..(n - 1).toDouble()) return null
            val ix = floor(x).toInt().coerceAtMost(n - 2)
            val iy = floor(y).toInt().coerceAtMost(n - 2)
            val i = iy * n + ix
            val a = heights[i]; val b = heights[i + 1]; val c = heights[i + n]; val d = heights[i + n + 1]
            if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) return null
            val fx = x - ix; val fy = y - iy
            return if (fx + fy <= 1) a + fx * (b - a) + fy * (c - a)
                else d + (1 - fx) * (c - d) + (1 - fy) * (b - d)
        }
    }
}
