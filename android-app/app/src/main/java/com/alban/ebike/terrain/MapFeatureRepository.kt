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
class MapFeatureRepository(context: Context) {
    private val cache = File(context.filesDir, "map-features-v1")
    private var memory: MapFeatureArea? = null
    private var retryAt = 0L

    suspend fun load(terrain: TerrainGrid): MapFeatureArea? = withContext(Dispatchers.IO) {
        val lat = terrain.originLat; val lon = terrain.originLon
        val half = terrain.halfSizeM.coerceAtMost(12_000.0)
        val detailed = half <= 3500
        fun covers(area: MapFeatureArea) = (!detailed || area.detailed) &&
            IgnElevationPolicy.contains(area.latitude, area.longitude, area.halfSizeM, lat, lon, half, 1.0)
        memory?.takeIf(::covers)?.let { return@withContext it }
        for (file in cache.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()) {
            val area = runCatching {
                if (file.length() > MAX_BYTES) return@runCatching null
                val json = JSONObject(file.readText())
                val header = MapFeatureArea(json.getDouble("lat"), json.getDouble("lon"),
                    json.getDouble("half"), json.getBoolean("detailed"), emptyList())
                if (!covers(header)) null else header.copy(features = parse(json.getJSONObject("data")))
            }.getOrNull()
            if (area != null) { memory = area; Log.i("EBikeMap", "OSM cache reused"); return@withContext area }
        }
        if (SystemClock.elapsedRealtime() < retryAt || abs(lat) > 84) return@withContext null
        val fetchHalf = half * 1.2
        val sw = GeoFrame.coordinate(-fetchHalf, -fetchHalf, lat, lon)
        val ne = GeoFrame.coordinate(fetchHalf, fetchHalf, lat, lon)
        if (sw.second >= ne.second) return@withContext null
        val bbox = "${sw.first},${sw.second},${ne.first},${ne.second}"
        val roads = if (detailed) "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|cycleway|track|path|footway|.*_link"
            else "motorway|trunk|primary|secondary|tertiary|unclassified|.*_link"
        val waterways = if (detailed) "river|stream|canal|drain|ditch" else "river|canal"
        val query = "[out:json][timeout:25];(way[highway~\"^($roads)$\"]($bbox);way[waterway~\"^($waterways)$\"]($bbox););out tags geom($bbox);"
        try {
            val json = network.withLock {
                delay((nextRequestAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                ensureActive()
                nextRequestAt = SystemClock.elapsedRealtime() + 3000
                request(query)
            }
            ensureActive()
            check(!json.has("remark")) { "Incomplete Overpass response" }
            val area = MapFeatureArea(lat, lon, fetchHalf, detailed, parse(json))
            memory = area
            runCatching {
                check(cache.isDirectory || cache.mkdirs())
                val key = MessageDigest.getInstance("SHA-256").digest("$lat/$lon/$half/$detailed".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val part = File(cache, "$key.part")
                part.writeText(JSONObject().put("lat", lat).put("lon", lon).put("half", fetchHalf)
                    .put("detailed", detailed).put("data", json).toString())
                check(part.renameTo(File(cache, "$key.json")))
                var total = cache.listFiles()?.sumOf { it.length() } ?: 0
                for (file in cache.listFiles { f -> f.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()) {
                    if (total <= 64L * 1024 * 1024) break
                    val length = file.length(); if (file.delete()) total -= length
                }
            }.onFailure { Log.w("EBikeMap", "OSM cache write failed", it) }
            Log.i("EBikeMap", "OSM loaded: ${area.features.count { !it.water }} roads, ${area.features.count { it.water }} waterways")
            area
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            retryAt = SystemClock.elapsedRealtime() + 120_000
            Log.w("EBikeMap", "OSM unavailable; preserving existing layers", error)
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
            val major = if (water) tags.optString("waterway") in listOf("river", "canal")
                else tags.optString("highway") !in listOf("service", "cycleway", "track", "path", "footway")
            val geometry = way.optJSONArray("geometry") ?: continue
            var points = ArrayList<MapCoordinate>()
            fun flush() { if (points.size > 1) result.add(MapFeature(water, major, points)); points = ArrayList() }
            for (n in 0 until geometry.length()) {
                val node = geometry.optJSONObject(n)
                val lat = node?.optDouble("lat", Double.NaN) ?: Double.NaN
                val lon = node?.optDouble("lon", Double.NaN) ?: Double.NaN
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) { flush(); continue }
                points.add(MapCoordinate(lat, lon))
                if (++totalPoints >= 150_000) { flush(); return result }
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
