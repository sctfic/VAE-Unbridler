package com.alban.ebike.terrain

import kotlin.math.*

/** A complete ring; direction changes priority, never coverage. */
object NeighbourPrefetch {
    fun expanded(keys: List<ElevationTileKey>, focus: ElevationTileKey, east: Double, north: Double): List<ElevationTileKey> {
        val length = hypot(east, north).coerceAtLeast(1.0)
        return keys.flatMap { ElevationTiles.neighbours(it) }.distinct()
            .sortedWith(compareBy<ElevationTileKey> { max(abs(it.x - focus.x), abs(it.y - focus.y)) }
                .thenBy { -((it.x - focus.x) * east + (it.y - focus.y) * north) / length })
    }
    fun ordered(center: ElevationTileKey, east: Double = 0.0, north: Double = 0.0): List<ElevationTileKey> {
        val length = hypot(east, north).coerceAtLeast(1.0)
        return ElevationTiles.neighbours(center).sortedWith(compareBy<ElevationTileKey> {
            if (it == center) -100.0 else {
                val dx = it.x - center.x; val dy = it.y - center.y
                -(dx * east + dy * north) / length + .1 * hypot(dx.toDouble(), dy.toDouble())
            }
        }.thenBy { it.x }.thenBy { it.y })
    }

    fun covering(grid: TerrainGrid, level: Int): List<ElevationTileKey> {
        val half = grid.halfSizeM.coerceAtMost(12_000.0)
        val sw = com.alban.ebike.scene.GeoFrame.coordinate(-half, -half, grid.originLat, grid.originLon)
        val ne = com.alban.ebike.scene.GeoFrame.coordinate(half, half, grid.originLat, grid.originLon)
        val a = ElevationTiles.key(sw.first, sw.second, level)
        val b = ElevationTiles.key(ne.first, ne.second, level)
        val center = ElevationTiles.key(grid.originLat, grid.originLon, level)
        return (a.x..b.x).flatMap { x -> (a.y..b.y).map { y -> ElevationTileKey(level, x, y) } }
            .sortedBy { hypot((it.x - center.x).toDouble(), (it.y - center.y).toDouble()) }.take(256)
    }
}
