package com.alban.ebike.terrain

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.alban.ebike.scene.GeoFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/** Cache-first OSM ways. Never blocks or replaces the IGN terrain. */
class MapFeatureRepository(context: Context, private val onBytes: (Int) -> Unit = {}) {
    @Volatile var pinWrites = false
    private val cache = File(context.filesDir, "map-features-v2")
    @Volatile private var memory: MapFeatureArea? = null
    @Volatile private var retryAt = 0L

    fun clearMemory() { memory = null; retryAt = 0 }

    suspend fun load(terrain: TerrainGrid, progress: (String) -> Unit = {}): MapFeatureArea? {
        val sw = GeoFrame.coordinate(-terrain.halfSizeM, -terrain.halfSizeM, terrain.originLat, terrain.originLon)
        val ne = GeoFrame.coordinate(terrain.halfSizeM, terrain.halfSizeM, terrain.originLat, terrain.originLon)
        val a = ElevationTiles.key(sw.first, sw.second, 2); val b = ElevationTiles.key(ne.first, ne.second, 2)
        val found = ArrayList<MapFeature>()
        var complete = true
        for (x in a.x..b.x) for (y in a.y..b.y) {
            val area = loadTile(ElevationTileKey(2, x, y), progress)
            if (area == null) complete = false else { found.addAll(area.features); complete = complete && area.complete }
        }
        return if (found.isEmpty() && !complete) null else MapFeatureArea(terrain.originLat,
            terrain.originLon, terrain.halfSizeM, true, found, complete)
    }
    suspend fun loadTile(key: ElevationTileKey, progress: (String) -> Unit = {}): MapFeatureArea? {
        val center = ElevationTiles.coordinate((key.x + .5) * key.side, (key.y + .5) * key.side)
        val half = key.side / 2 * kotlin.math.cos(Math.toRadians(center.first))
        return loadArea(TerrainGrid(center.first, center.second, half, 2, FloatArray(4), "", false), progress)
    }
    suspend fun loadArea(terrain: TerrainGrid, progress: (String) -> Unit = {}): MapFeatureArea? = withContext(Dispatchers.IO) {
        progress("OSM · recherche cache")
        val lat = terrain.originLat; val lon = terrain.originLon
        val half = terrain.halfSizeM.coerceAtMost(12_000.0)
        val detailed = true // Layer visibility is independent of the 3D window.
        fun covers(area: MapFeatureArea) = (!detailed || area.detailed) &&
            IgnElevationPolicy.contains(area.latitude, area.longitude, area.halfSizeM, lat, lon, half, 1.0)
        memory?.takeIf(::covers)?.let { progress("OSM · cache RAM"); return@withContext it }
        for (file in cache.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()) {
            val area = runCatching {
                if (file.length() > MAX_BYTES) return@runCatching null
                val json = JSONObject(file.readText())
                val header = MapFeatureArea(json.getDouble("lat"), json.getDouble("lon"),
                    json.getDouble("half"), json.getBoolean("detailed"), emptyList())
                if (!covers(header)) null else header.copy(features = parse(json.getJSONObject("data")))
            }.getOrNull()
            if (area != null) { memory = area; file.setLastModified(System.currentTimeMillis()); progress("OSM · cache disque décodé"); Log.i("EBikeMap", "OSM cache reused"); return@withContext area }
        }
        if (SystemClock.elapsedRealtime() < retryAt || abs(lat) > 84) { progress("OSM · temporisation / zone indisponible"); return@withContext null }
        val fetchHalf = half * 1.2
        val sw = GeoFrame.coordinate(-fetchHalf, -fetchHalf, lat, lon)
        val ne = GeoFrame.coordinate(fetchHalf, fetchHalf, lat, lon)
        if (sw.second >= ne.second) return@withContext null
        val bbox = "${sw.first},${sw.second},${ne.first},${ne.second}"
        val roads = if (detailed) "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|cycleway|track|path|footway|steps|bridleway|.*_link"
            else "motorway|trunk|primary|secondary|tertiary|unclassified|.*_link"
        val waterways = if (detailed) "river|stream|canal|drain|ditch" else "river|canal"
        val buildings = if (detailed) "way[building][building!=no]($bbox);" else ""
        val query = "[out:json][timeout:25];(way[highway~\"^($roads)$\"]($bbox);way[waterway~\"^($waterways)$\"]($bbox);$buildings);out tags geom($bbox);"
        try {
            val json = network.withLock {
                progress("OSM · attente quota Overpass")
                delay((nextRequestAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                ensureActive()
                nextRequestAt = SystemClock.elapsedRealtime() + 3000
                progress("OSM · téléchargement Overpass")
                request(query)
            }
            ensureActive()
            check(!json.has("remark")) { "Incomplete Overpass response" }
            progress("OSM · décodage et sauvegarde")
            val area = MapFeatureArea(lat, lon, fetchHalf, detailed, parse(json))
            memory = area
            val saved = runCatching {
                check(cache.isDirectory || cache.mkdirs())
                val key = MessageDigest.getInstance("SHA-256").digest("$lat/$lon/$half/$detailed".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val part = File.createTempFile("osm-", ".part", cache)
                part.writeText(JSONObject().put("lat", lat).put("lon", lon).put("half", fetchHalf)
                    .put("detailed", detailed).put("data", json).toString())
                check(part.renameTo(File(cache, "$key.json")))
                if (pinWrites) File(cache, "$key.json.pin").writeText("")
                var total = cache.listFiles()?.sumOf { it.length() } ?: 0
                for (file in cache.listFiles { f -> f.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()) {
                    if (total <= 64L * 1024 * 1024) break
                    if (File(cache, file.name + ".pin").exists()) continue
                    val length = file.length(); if (file.delete()) total -= length
                }
            }.onFailure { Log.w("EBikeMap", "OSM cache write failed", it) }.isSuccess
            Log.i("EBikeMap", "OSM loaded: ${area.features.count { !it.water }} roads, ${area.features.count { it.water }} waterways")
            if (!saved) memory = null
            area.copy(complete = saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            retryAt = SystemClock.elapsedRealtime() + 120_000
            Log.w("EBikeMap", "OSM unavailable; preserving existing layers", error)
            progress("OSM · échec réseau, couches conservées")
            null
        }
    }

    private fun parse(json: JSONObject): List<MapFeature> {
        val elements = json.getJSONArray("elements")
        val result = ArrayList<MapFeature>()
        var totalPoints = 0
        for (i in 0 until elements.length()) {
            val way = elements.optJSONObject(i) ?: continue
            val tags = way.optJSONObject("tags") ?: continue
            if (tags.optString("tunnel") in listOf("yes", "culvert", "building_passage")) continue
            val water = tags.has("waterway")
            val building = tags.has("building") && !tags.has("highway") && !water
            val path = tags.optString("highway") in listOf("cycleway", "track", "path", "footway", "steps", "bridleway")
            val major = if (water) tags.optString("waterway") in listOf("river", "canal")
                else !building && !path && tags.optString("highway") != "service"
            val geometry = way.optJSONArray("geometry") ?: continue
            var points = ArrayList<MapCoordinate>()
            fun flush() { if (points.size > 1) result.add(MapFeature(water, major, points, path, building)); points = ArrayList() }
            for (n in 0 until geometry.length()) {
                val node = geometry.optJSONObject(n)
                val lat = node?.optDouble("lat", Double.NaN) ?: Double.NaN
                val lon = node?.optDouble("lon", Double.NaN) ?: Double.NaN
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) { flush(); continue }
                points.add(MapCoordinate(lat, lon))
                check(++totalPoints < 150_000) { "OSM zone trop dense : données incomplètes" }
            }
            flush()
        }
        return result
    }

    private fun request(query: String): JSONObject {
        val connection = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 8000; connection.readTimeout = 35000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "EBikeCockpit/1.0 (https://github.com/sctfic/VAE-Unbridler)")
            connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            check(connection.responseCode == 200) { "OSM HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= MAX_BYTES) { "OSM response too large" }
                    onBytes(count)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val network = Mutex()
        private var nextRequestAt = 0L
    }
}
