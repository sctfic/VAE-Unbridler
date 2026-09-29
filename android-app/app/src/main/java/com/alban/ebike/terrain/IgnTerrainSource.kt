package com.alban.ebike.terrain

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Numeric ground elevations from RGE ALTI 1m; no rendered map image decoding. IO caller only. */
class IgnTerrainSource(context: Context) {
    data class Result(val originLat: Double, val originLon: Double, val halfSizeM: Double,
        val heights: FloatArray, val cached: Boolean) {
        val size: Int get() = kotlin.math.sqrt(heights.size.toDouble()).toInt()
    }
    private val cache = File(context.cacheDir, "terrain-ign-rge1m-v1")
    private var retryAt = 0L
    private val emptyUntil = LinkedHashMap<String, Long>()

    suspend fun load(lat: Double, lon: Double, halfSize: Double, size: Int = IgnElevationPolicy.SIZE): Result? {
        val previous = findCoveringCache(lat, lon, halfSize)
        previous?.takeIf { IgnElevationPolicy.adequateResolution(it.halfSizeM, it.size, halfSize, size) }?.let {
            Log.i("EBikeTerrain", "IGN cache reused without network")
            return it
        }
        val coordinates = IgnElevationPolicy.coordinates(lat, lon, halfSize, size)
        val indices = coordinates.indices.filter { coordinates[it].let { p -> IgnElevationPolicy.mayCover(p.first, p.second) } }
        if (indices.isEmpty()) return null
        val key = MessageDigest.getInstance("SHA-256").digest("$size/$lat/$lon/$halfSize".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(cache, "$key.bin")
        val stored = runCatching {
            check(file.length() == coordinates.size * 4L)
            DataInputStream(file.inputStream().buffered()).use { input ->
                FloatArray(coordinates.size) { IgnElevationPolicy.elevation(input.readFloat().toDouble()) }
            }.takeIf { heights -> heights.any { it.isFinite() } }
        }.getOrNull()
        if (stored != null) {
            writeMetadata(key, lat, lon, halfSize, size)
            return Result(lat, lon, halfSize, stored, true)
        }
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt || now < (emptyUntil[key] ?: 0L)) return previous
        try {
            val heights = FloatArray(coordinates.size) { Float.NaN }
            for (batch in indices.chunked(IgnElevationPolicy.BATCH_SIZE)) {
            val body = JSONObject().put("resource", IgnElevationPolicy.RESOURCE)
                .put("lon", batch.joinToString("|") { coordinates[it].second.toString() })
                .put("lat", batch.joinToString("|") { coordinates[it].first.toString() })
                .put("delimiter", "|").put("zonly", "true").put("measures", "false").toString()
            val values = networkMutex.withLock {
                // <= 1 request/sec across scene instances (official service limit: 5/sec/IP).
                delay((nextRequestAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                coroutineContext.ensureActive()
                nextRequestAt = SystemClock.elapsedRealtime() + 1000
                request(body)
            }
            coroutineContext.ensureActive()
            val elevations = JSONObject(values).getJSONArray("elevations")
            check(elevations.length() == batch.size) { "Unexpected IGN response size" }
            batch.forEachIndexed { n, index -> heights[index] = IgnElevationPolicy.elevation(elevations.optDouble(n, Double.NaN)) }
            }
            val valid = heights.count { it.isFinite() }
            Log.i("EBikeTerrain", "IGN RGE ALTI 1m: $valid/${heights.size} samples")
            if (valid == 0) {
                if (emptyUntil.size >= 32) emptyUntil.clear()
                emptyUntil[key] = now + 300_000
                return previous
            }
            runCatching {
                check(cache.isDirectory || cache.mkdirs())
                val partial = File(cache, "$key.part")
                DataOutputStream(partial.outputStream().buffered()).use { output -> heights.forEach { output.writeFloat(it) } }
                check(partial.renameTo(file))
                writeMetadata(key, lat, lon, halfSize, size)
                prune()
            }.onFailure { Log.w("EBikeTerrain", "IGN cache write failed", it) }
            return Result(lat, lon, halfSize, heights, false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            retryAt = SystemClock.elapsedRealtime() + 120_000
            Log.w("EBikeTerrain", "IGN unavailable; cached/fallback terrain", error)
            return previous
        }
    }

    private fun findCoveringCache(lat: Double, lon: Double, halfSize: Double): Result? {
        val metadata = cache.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() } ?: return null
        var best: Result? = null
        for (meta in metadata) {
            val result = runCatching {
                val json = JSONObject(meta.readText())
                val cachedLat = json.getDouble("lat")
                val cachedLon = json.getDouble("lon")
                val cachedHalf = json.getDouble("halfSizeM")
                if (!IgnElevationPolicy.contains(cachedLat, cachedLon, cachedHalf, lat, lon, halfSize)) return@runCatching null
                val file = File(cache, meta.nameWithoutExtension + ".bin")
                val size = json.optInt("size", 65)
                check(size in listOf(65, 97, 129))
                check(file.length() == size * size * 4L)
                val heights = DataInputStream(file.inputStream().buffered()).use { input ->
                    FloatArray(size * size) {
                        IgnElevationPolicy.elevation(input.readFloat().toDouble())
                    }
                }
                Result(cachedLat, cachedLon, cachedHalf, heights, true).takeIf { result -> result.heights.any { it.isFinite() } }
            }.getOrNull()
            if (result != null && (best == null || result.halfSizeM / (result.size - 1) <
                    best.halfSizeM / (best.size - 1))) best = result
        }
        return best
    }

    private fun writeMetadata(key: String, lat: Double, lon: Double, halfSize: Double, size: Int) {
        runCatching {
            check(cache.isDirectory || cache.mkdirs())
            val target = File(cache, "$key.json")
            if (!target.exists()) target.writeText(JSONObject().put("lat", lat).put("lon", lon)
                .put("halfSizeM", halfSize).put("size", size).toString())
        }.onFailure { Log.w("EBikeTerrain", "IGN cache metadata write failed", it) }
    }

    private fun request(body: String): String {
        val connection = URL("https://data.geopf.fr/altimetrie/1.0/calcul/alti/rest/elevation.json").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000; connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "EBikeTerrain/1.0")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "IGN HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val block = ByteArray(8192)
                while (true) {
                    val count = input.read(block); if (count < 0) break
                    check(out.size() + count <= 1024 * 1024)
                    out.write(block, 0, count)
                }
                out.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
    private fun prune() {
        val files = cache.listFiles { f -> f.extension == "bin" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 64L * 1024 * 1024) break
            val length = file.length(); if (file.delete()) size -= length
            File(cache, file.nameWithoutExtension + ".json").delete()
        }
    }
    companion object {
        private val networkMutex = Mutex()
        private var nextRequestAt = 0L
    }
}
