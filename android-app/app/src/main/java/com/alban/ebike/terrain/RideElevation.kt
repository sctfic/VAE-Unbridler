package com.alban.ebike.terrain

import com.alban.ebike.scene.GeoFrame

/** Measurement grid, independent of the rendering window and vertical exaggeration. */
object RideElevation {
    const val HALF_SIZE = 160.0
    const val SIZE = 65 // 5 m sampling, identical in every display mode.

    /** Reuse the fine IGN tiles already downloaded by the map, including offline. */
    fun cachedGrid(store: ElevationTileStore, latitude: Double, longitude: Double): TerrainGrid? {
        val focus = ElevationTiles.key(latitude, longitude)
        val tiles = ElevationTiles.neighbours(focus).mapNotNull { key -> store.read(key)?.let { key to it } }.toMap()
        if (tiles.isEmpty()) return null
        val heights = FloatArray(SIZE * SIZE) { i ->
            val point = GeoFrame.coordinate((i % SIZE - 32) * 5.0, (i / SIZE - 32) * 5.0, latitude, longitude)
            val key = ElevationTiles.key(point.first, point.second)
            val projected = ElevationTiles.projected(point.first, point.second)
            tiles[key]?.let { ElevationTiles.sample(key, it, projected.first, projected.second)?.toFloat() } ?: Float.NaN
        }
        val result = TerrainGrid(latitude, longitude, HALF_SIZE, SIZE, heights, "IGN · TUILES CACHE", true)
        return result.takeIf { it.sample(0.0, 0.0) != null }
    }

    /** Altitude envelope caused by horizontal position uncertainty; excludes unknown DEM vertical error. */
    fun positionUncertainty(grid: TerrainGrid?, latitude: Double, longitude: Double, accuracyM: Float): Float? {
        val center = sample(grid, latitude, longitude, accuracyM) ?: return null
        val p = GeoFrame.local(latitude, longitude, grid!!.originLat, grid.originLon)
        val nearby = (0 until 8).mapNotNull { i ->
            val angle = i * Math.PI / 4
            grid.sample(p.east + accuracyM * kotlin.math.cos(angle), p.north + accuracyM * kotlin.math.sin(angle))
        }
        if (nearby.size != 8) return null
        return nearby.maxOf { kotlin.math.abs(it - center) }.toFloat()
    }
    fun sample(grid: TerrainGrid?, latitude: Double, longitude: Double, accuracyM: Float): Float? {
        if (grid == null || !grid.real || !accuracyM.isFinite() || accuracyM !in 0f..10f ||
            grid.halfSizeM * 2 / (grid.size - 1) > 5.01) return null
        val p = GeoFrame.local(latitude, longitude, grid.originLat, grid.originLon)
        return grid.sample(p.east, p.north)?.toFloat()
    }
}
