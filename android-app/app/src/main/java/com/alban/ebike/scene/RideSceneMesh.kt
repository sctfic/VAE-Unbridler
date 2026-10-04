package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import com.alban.ebike.terrain.TerrainGrid
import com.alban.ebike.terrain.TerrainDetail
import com.alban.ebike.terrain.GeometryCache
import kotlin.math.*

data class SceneMesh(val surface: FloatArray, val grid: FloatArray, val contours: FloatArray,
    val route: FloatArray, val marker: FloatArray, val frame: List<WorldPoint>,
    val center: WorldPoint, val heading: Double, val speedMode: Boolean,
    val routeColors: FloatArray = floatArrayOf(),
    val roads: FloatArray = floatArrayOf(), val waterways: FloatArray = floatArrayOf(),
    val paths: FloatArray = floatArrayOf(), val buildings: FloatArray = floatArrayOf(),
    val parcels: FloatArray = floatArrayOf(), val parcelLabels: List<com.alban.ebike.terrain.WorldLabel> = emptyList())

object RideSceneMesh {
    const val VERTICAL_EXAGGERATION = 1.8
    private data class TerrainLayers(val surface: FloatArray, val grid: FloatArray, val contours: FloatArray)
    private var cachedTerrain: TerrainGrid? = null
    private var cachedDetail: TerrainDetail? = null
    private var cachedLayers: TerrainLayers? = null

    @Synchronized private fun layers(terrain: TerrainGrid?, detail: TerrainDetail,
        cache: GeometryCache?, progress: (String) -> Unit): TerrainLayers {
        if (cachedTerrain === terrain && cachedDetail == detail) cachedLayers?.let { progress("Maillage · RAM"); return it }
        val key = if (terrain?.real == true && cache != null) GeometryCache.key(terrain, detail) else null
        if (key != null) {
            progress("Maillage · recherche cache")
            cache?.read(key)?.takeIf { it.first.size == 3 }?.let { (arrays, source) ->
                progress("Maillage · cache $source")
                return TerrainLayers(arrays[0], arrays[1], arrays[2]).also {
                    cachedTerrain = terrain; cachedDetail = detail; cachedLayers = it
                }
            }
        }
        progress("Maillage · interpolation / triangles / courbes")
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
            if (key != null) {
                progress("Maillage · sauvegarde cache")
                cache?.write(key, listOf(it.surface, it.grid, it.contours))
            }
            cachedTerrain = terrain; cachedDetail = detail; cachedLayers = it
        }
    }

    fun build(track: List<TrackPoint>, terrain: TerrainGrid?, speedMode: Boolean, heading: Double,
        metric: RouteMetric = RouteMetric.SPEED, detail: TerrainDetail = TerrainDetail.CLOSE,
        cache: GeometryCache? = null, pauses: List<com.alban.ebike.model.RidePause> = emptyList(), progress: (String) -> Unit = {}): SceneMesh {
        val originLat = terrain?.originLat ?: track.lastOrNull()?.latitude ?: 0.0
        val originLon = terrain?.originLon ?: track.lastOrNull()?.longitude ?: 0.0
        val base = terrain?.heights?.filter { it.isFinite() }?.minOrNull()?.toDouble() ?: 0.0
        val ground = layers(terrain, detail, cache, progress)
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
                val ca = RouteColors.color(track[i], metric, range)
                val cb = RouteColors.color(track[i + 1], metric, range)
                fun point(t: Float) = WorldPoint(a.east + (b.east - a.east) * t,
                    a.north + (b.north - a.north) * t, a.height + (b.height - a.height) * t)
                com.alban.ebike.model.restSections(track[i].distanceM, track[i + 1].distanceM, pauses).forEach { (from, to, resting) ->
                    line(route, point(from), point(to))
                    for (t in listOf(from, to)) colors.addAll(if (resting) listOf(1f, 0f, 1f)
                        else (0..2).map { ca[it] + (cb[it] - ca[it]) * t })
                }
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
