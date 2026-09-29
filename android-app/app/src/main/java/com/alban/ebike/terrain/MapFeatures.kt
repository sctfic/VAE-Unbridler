package com.alban.ebike.terrain

import com.alban.ebike.scene.GeoFrame
import com.alban.ebike.scene.RideSceneMesh
import com.alban.ebike.scene.WorldPoint
import kotlin.math.*

data class MapCoordinate(val latitude: Double, val longitude: Double)
data class MapFeature(val water: Boolean, val major: Boolean, val points: List<MapCoordinate>,
    val path: Boolean = false, val building: Boolean = false)
data class MapFeatureArea(val latitude: Double, val longitude: Double, val halfSizeM: Double,
    val detailed: Boolean, val features: List<MapFeature>)
data class MapFeatureMesh(val roads: FloatArray = floatArrayOf(), val water: FloatArray = floatArrayOf(),
    val paths: FloatArray = floatArrayOf(), val buildings: FloatArray = floatArrayOf())

object MapFeatureProjection {
    /** Clip before sampling: missing terrain must not create lines through the zero plane. */
    fun clip(a: WorldPoint, b: WorldPoint, half: Double): Pair<WorldPoint, WorldPoint>? {
        var low = 0.0; var high = 1.0
        val dx = b.east - a.east; val dy = b.north - a.north
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(a.east + half, half - a.east, a.north + half, half - a.north)
        for (i in p.indices) {
            if (abs(p[i]) < 1e-10) { if (q[i] < 0) return null }
            else {
                val t = q[i] / p[i]
                if (p[i] < 0) low = max(low, t) else high = min(high, t)
                if (low > high) return null
            }
        }
        return WorldPoint(a.east + low * dx, a.north + low * dy) to
            WorldPoint(a.east + high * dx, a.north + high * dy)
    }

    fun build(area: MapFeatureArea?, terrain: TerrainGrid?, detail: TerrainDetail): MapFeatureMesh {
        if (area == null || terrain == null || !terrain.real) return MapFeatureMesh()
        val base = terrain.heights.filter { it.isFinite() }.minOrNull()?.toDouble() ?: return MapFeatureMesh()
        val roads = ArrayList<Float>(); val water = ArrayList<Float>()
        val paths = ArrayList<Float>(); val buildings = ArrayList<Float>()
        fun result() = MapFeatureMesh(roads.toFloatArray(), water.toFloatArray(), paths.toFloatArray(), buildings.toFloatArray())
        val spacing = (terrain.halfSizeM * 2 / (terrain.size - 1) / 2).coerceIn(3.0, 80.0)
        fun add(target: MutableList<Float>, p: WorldPoint) {
            target.add(p.east.toFloat()); target.add(p.north.toFloat()); target.add(p.height.toFloat())
        }
        for (feature in area.features) {
            if (detail == TerrainDetail.OVERVIEW && !feature.major) continue
            val target = when { feature.water -> water; feature.building -> buildings; feature.path -> paths; else -> roads }
            for ((a, b) in feature.points.zipWithNext()) {
                if (roads.size + water.size + paths.size + buildings.size >= 600_000) return result()
                val segment = clip(GeoFrame.local(a.latitude, a.longitude, terrain.originLat, terrain.originLon),
                    GeoFrame.local(b.latitude, b.longitude, terrain.originLat, terrain.originLon), terrain.halfSizeM) ?: continue
                val start = segment.first; val end = segment.second
                val count = ceil(hypot(end.east - start.east, end.north - start.north) / spacing).toInt().coerceIn(1, 1024)
                var previous: WorldPoint? = null
                for (i in 0..count) {
                    val t = i.toDouble() / count
                    val east = start.east + (end.east - start.east) * t
                    val north = start.north + (end.north - start.north) * t
                    val elevation = terrain.sampleSmooth(east, north)
                    val point = elevation?.let { WorldPoint(east, north, (it - base) * RideSceneMesh.VERTICAL_EXAGGERATION + 1.2) }
                    if (point != null && previous != null) { add(target, previous); add(target, point) }
                    previous = point
                }
            }
        }
        return result()
    }
}
