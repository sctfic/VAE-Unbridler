package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import com.alban.ebike.terrain.TerrainGrid
import com.alban.ebike.terrain.TerrainDetail
import kotlin.math.*

data class SceneMesh(val surface: FloatArray, val grid: FloatArray, val contours: FloatArray,
    val route: FloatArray, val marker: FloatArray, val frame: List<WorldPoint>,
    val center: WorldPoint, val heading: Double, val speedMode: Boolean,
    val routeColors: FloatArray = floatArrayOf(),
    val roads: FloatArray = floatArrayOf(), val waterways: FloatArray = floatArrayOf())

object RideSceneMesh {
    const val VERTICAL_EXAGGERATION = 1.8
    private data class TerrainLayers(val surface: FloatArray, val grid: FloatArray, val contours: FloatArray)
    private var cachedTerrain: TerrainGrid? = null
    private var cachedDetail: TerrainDetail? = null
    private var cachedLayers: TerrainLayers? = null

    @Synchronized private fun layers(terrain: TerrainGrid?, detail: TerrainDetail): TerrainLayers {
        if (cachedTerrain === terrain && cachedDetail == detail) cachedLayers?.let { return it }
        val base = terrain?.heights?.filter { it.isFinite() }?.minOrNull()?.toDouble() ?: 0.0
        val surface = ArrayList<Float>(); val grid = ArrayList<Float>(); val contours = ArrayList<Float>()
        fun add(list: MutableList<Float>, p: WorldPoint) { list.add(p.east.toFloat()); list.add(p.north.toFloat()); list.add(p.height.toFloat()) }
        fun line(list: MutableList<Float>, a: WorldPoint, b: WorldPoint) { add(list, a); add(list, b) }
        // Dense IGN measurements retain their detail; interpolation refines the contour segments.
        val n = if (terrain == null) 65 else min(detail.renderSize, if (terrain.size >= 97) terrain.size * 2 - 1 else 129)
        val gridStride = (n - 1) / 32
        val contourStep = detail.contourM
        val half = terrain?.halfSizeM ?: 500.0
        val sampled = Array<Double?>(n * n) { i ->
            val east = (i % n / (n - 1.0) * 2 - 1) * half
            val north = (i / n / (n - 1.0) * 2 - 1) * half
            terrain?.sampleSmooth(east, north)
        }
        val vertices = Array(n * n) { i ->
            val east = (i % n / (n - 1.0) * 2 - 1) * half
            val north = (i / n / (n - 1.0) * 2 - 1) * half
            val h = sampled[i]
            WorldPoint(east, north, if (h != null && h.isFinite()) h - base else 0.0)
        }
        val valid = BooleanArray(n * n) { i -> sampled[i]?.isFinite() ?: false }
        fun visual(point: WorldPoint, lift: Double = 0.0) =
            point.copy(height = point.height * VERTICAL_EXAGGERATION + lift)
        fun triangle(a: WorldPoint, b: WorldPoint, c: WorldPoint) {
            add(surface, visual(a)); add(surface, visual(b)); add(surface, visual(c))
            val low = minOf(a.height, b.height, c.height)
            val high = maxOf(a.height, b.height, c.height)
            var level = ceil((low + base) / contourStep) * contourStep - base
            var count = 0
            while (level < high && count++ < 60) {
                val cuts = ArrayList<WorldPoint>(3)
                for ((p, q) in listOf(a to b, b to c, c to a)) {
                    if ((p.height <= level && q.height > level) || (q.height <= level && p.height > level)) {
                        val t = (level - p.height) / (q.height - p.height)
                        cuts.add(WorldPoint(p.east + t * (q.east - p.east), p.north + t * (q.north - p.north), level))
                    }
                }
                if (cuts.size == 2) line(contours, visual(cuts[0], .5), visual(cuts[1], .5))
                level += contourStep
            }
        }
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val i = y * n + x
            if (valid[i] && valid[i + 1] && valid[i + n] && valid[i + n + 1]) {
                triangle(vertices[i], vertices[i + 1], vertices[i + n])
                triangle(vertices[i + 1], vertices[i + n + 1], vertices[i + n])
                if (y % gridStride == 0) line(grid, visual(vertices[i], .3), visual(vertices[i + 1], .3))
                if (x % gridStride == 0) line(grid, visual(vertices[i], .3), visual(vertices[i + n], .3))
            } else if (terrain == null || !terrain.real) {
                if (y % 4 == 0) line(grid, vertices[i], vertices[i + 1])
                if (x % 4 == 0) line(grid, vertices[i], vertices[i + n])
            }
        }
        return TerrainLayers(surface.toFloatArray(), grid.toFloatArray(), contours.toFloatArray()).also {
            cachedTerrain = terrain; cachedDetail = detail; cachedLayers = it
        }
    }

    fun build(track: List<TrackPoint>, terrain: TerrainGrid?, speedMode: Boolean, heading: Double,
        metric: RouteMetric = RouteMetric.SPEED, detail: TerrainDetail = TerrainDetail.CLOSE): SceneMesh {
        val originLat = terrain?.originLat ?: track.lastOrNull()?.latitude ?: 0.0
        val originLon = terrain?.originLon ?: track.lastOrNull()?.longitude ?: 0.0
        val base = terrain?.heights?.filter { it.isFinite() }?.minOrNull()?.toDouble() ?: 0.0
        val ground = layers(terrain, detail)
        fun add(list: MutableList<Float>, p: WorldPoint) { list.add(p.east.toFloat()); list.add(p.north.toFloat()); list.add(p.height.toFloat()) }
        fun line(list: MutableList<Float>, a: WorldPoint, b: WorldPoint) { add(list, a); add(list, b) }
        // Drape the route onto the DEM, avoiding mixing GPS ellipsoid and DEM datums.
        // This is a ground-projected path, not a measured bridge/tunnel elevation.
        val positions = track.map { point ->
            val p = GeoFrame.local(point.latitude, point.longitude, originLat, originLon)
            p.copy(height = (terrain?.sampleSmooth(p.east, p.north)?.minus(base) ?: 0.0) * VERTICAL_EXAGGERATION + 3)
        }
        val route = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val range = RouteColors.range(track, metric)
        positions.zipWithNext().forEachIndexed { i, (a, b) ->
            if (!track[i + 1].segmentStart) {
                line(route, a, b)
                colors.addAll(RouteColors.color(track[i], metric, range).toList())
                colors.addAll(RouteColors.color(track[i + 1], metric, range).toList())
            }
        }
        val frame = positions.ifEmpty { listOf(WorldPoint(-45.0, -45.0), WorldPoint(45.0, 45.0)) }
        val center = WorldPoint((frame.minOf { it.east } + frame.maxOf { it.east }) / 2,
            (frame.minOf { it.north } + frame.maxOf { it.north }) / 2,
            (frame.minOf { it.height } + frame.maxOf { it.height }) / 2)
        return SceneMesh(ground.surface, ground.grid, ground.contours, route.toFloatArray(),
            positions.lastOrNull()?.let { floatArrayOf(it.east.toFloat(), it.north.toFloat(), it.height.toFloat()) } ?: floatArrayOf(),
            frame, center, heading, speedMode, colors.toFloatArray())
    }
}
