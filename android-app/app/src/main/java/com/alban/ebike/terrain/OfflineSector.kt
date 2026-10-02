package com.alban.ebike.terrain

import kotlin.math.*

object OfflineSector {
    /** Tiles intersecting a 90 degree sector, including a margin at its boundaries. */
    fun tiles(lat: Double, lon: Double, radiusKm: Int, direction: Int, level: Int): List<ElevationTileKey> {
        require(radiusKm in 2..50 && direction in 0..7)
        val origin = ElevationTiles.projected(lat, lon)
        val scale = cos(Math.toRadians(lat.coerceIn(-84.0, 84.0)))
        val radius = radiusKm * 1000.0 / scale
        val key = ElevationTiles.key(lat, lon, level)
        val count = ceil(radius / key.side).toInt() + 1
        val margin = key.side * sqrt(2.0) / 2
        return (-count..count).flatMap { x -> (-count..count).map { y -> key.copy(x = key.x + x, y = key.y + y) } }
            .filter {
                val east = (it.x + .5) * it.side - origin.first
                val north = (it.y + .5) * it.side - origin.second
                val distance = hypot(east, north)
                val angle = atan2(east, north) - direction * PI / 4
                val delta = abs(atan2(sin(angle), cos(angle)))
                distance <= radius + margin && (distance <= margin || delta <= PI / 4 + asin((margin / distance).coerceAtMost(1.0)))
            }.sortedBy { hypot((it.x + .5) * it.side - origin.first, (it.y + .5) * it.side - origin.second) }
    }
}
