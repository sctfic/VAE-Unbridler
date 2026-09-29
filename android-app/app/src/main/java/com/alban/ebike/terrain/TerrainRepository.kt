package com.alban.ebike.terrain

import android.content.Context
import android.graphics.BitmapFactory
import com.alban.ebike.scene.GeoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** Public Terrarium DEM, bounded disk cache; no API key or location telemetry. */
class TerrainRepository(context: Context) {
    private val ign = IgnTerrainSource(context)
    private val cache = File(context.cacheDir, "terrain-v1")
    private val memory = object : LinkedHashMap<TileKey, FloatArray>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, FloatArray>?) = size > 24
    }
    private val failedAt = mutableMapOf<TileKey, Long>()

    suspend fun load(lat: Double, lon: Double, halfSizeM: Double,
        detail: TerrainDetail = TerrainDetail.CLOSE): TerrainGrid = withContext(Dispatchers.IO) {
        val preferred = ign.load(lat, lon, halfSizeM, detail.sourceSize)
        val step = ((preferred?.halfSizeM ?: halfSizeM) * 2 / ((preferred?.size ?: IgnElevationPolicy.SIZE) - 1)).roundToInt()
        if (preferred != null && preferred.heights.all { it.isFinite() }) return@withContext TerrainGrid(
            preferred.originLat, preferred.originLon, preferred.halfSizeM, preferred.size, preferred.heights,
            "IGN RGE ALTI 1 M · MAILLE ${step} M" + if (preferred.cached) " · CACHE" else "", true)
        val fallback = loadMapzen(lat, lon, halfSizeM)
        if (preferred == null) fallback.copy(status = "MAPZEN · " + fallback.status)
        else {
            val heights = preferred.heights.copyOf()
            for (i in heights.indices) if (!heights[i].isFinite()) {
                val coordinate = GeoFrame.coordinate(
                    (i % preferred.size / (preferred.size - 1.0) * 2 - 1) * preferred.halfSizeM,
                    (i / preferred.size / (preferred.size - 1.0) * 2 - 1) * preferred.halfSizeM,
                    preferred.originLat, preferred.originLon)
                val point = GeoFrame.local(coordinate.first, coordinate.second, lat, lon)
                heights[i] = fallback.sample(point.east, point.north)?.toFloat() ?: Float.NaN
            }
            TerrainGrid(preferred.originLat, preferred.originLon, preferred.halfSizeM, preferred.size,
                heights, "IGN RGE ALTI + REPLI MAPZEN · MAILLE ${step} M", true)
        }
    }

    private suspend fun loadMapzen(lat: Double, lon: Double, halfSizeM: Double): TerrainGrid = withContext(Dispatchers.IO) {
        val size = 65
        if (abs(lat) > 84.0) return@withContext TerrainGrid(lat, lon, halfSizeM, size,
            FloatArray(size * size) { Float.NaN }, "ZONE POLAIRE · GRILLE", false)
        // At high latitudes Mercator tiles become small; cap the request area at 16 tiles.
        val groundTileWidth = 40075016.686 * cos(Math.toRadians(lat)) / 8192
        val zoom = (13 - ceil(log2(max(1.0, halfSizeM * 2 / (groundTileWidth * 2))))).toInt().coerceIn(0, 13)
        val heights = FloatArray(size * size) { Float.NaN }
        val samples = Array(size * size) { i ->
            val east = (i % size / (size - 1.0) * 2 - 1) * halfSizeM
            val north = (i / size / (size - 1.0) * 2 - 1) * halfSizeM
            val coord = GeoFrame.coordinate(east, north, lat, lon)
            TerrainCoordinates.tile(coord.first, coord.second, zoom)
        }
        // Fetch each tile once, including neighbouring tiles covering the visible terrain.
        val keys = samples.map { TileKey(zoom, floor(it.first).toInt(), floor(it.second).toInt()) }.distinct()
        val loaded = mutableMapOf<TileKey, FloatArray>()
        var downloads = 0
        var networkAllowed = true
        keys.forEach { key ->
            coroutineContext.ensureActive()
            val tile = runCatching { readTile(key, networkAllowed) }.getOrNull()
            if (tile != null) { loaded[key] = tile.first; if (tile.second) downloads++ }
            else networkAllowed = false // Offline: still read cached neighbours, avoid repeated network timeouts.
        }
        samples.forEachIndexed { i, (tx, ty) ->
            val key = TileKey(zoom, floor(tx).toInt(), floor(ty).toInt())
            val tile = loaded[key] ?: return@forEachIndexed
            val px = ((tx - floor(tx)) * 256).coerceIn(0.0, 255.0)
            val py = ((ty - floor(ty)) * 256).coerceIn(0.0, 255.0)
            val x = floor(px).toInt().coerceAtMost(254); val y = floor(py).toInt().coerceAtMost(254)
            val dx = (px - x).toFloat(); val dy = (py - y).toFloat()
            heights[i] = (tile[y * 256 + x] * (1 - dx) + tile[y * 256 + x + 1] * dx) * (1 - dy) +
                (tile[(y + 1) * 256 + x] * (1 - dx) + tile[(y + 1) * 256 + x + 1] * dx) * dy
        }
        val complete = loaded.size == keys.size
        pruneCache()
        TerrainGrid(lat, lon, halfSizeM, size, heights, when {
            loaded.isEmpty() -> "RELIEF INDISPONIBLE · GRILLE"
            !complete -> "RELIEF PARTIEL · ${loaded.size}/${keys.size} TUILES"
            downloads == 0 -> "RELIEF RÉEL · CACHE"
            else -> "RELIEF RÉEL · COURBES 10 M"
        }, loaded.isNotEmpty())
    }

    private fun readTile(key: TileKey, networkAllowed: Boolean): Pair<FloatArray, Boolean>? {
        memory[key]?.let { return it to false }
        check(cache.isDirectory || cache.mkdirs())
        val file = File(cache, key.fileName)
        if (file.exists()) {
            decode(file.readBytes())?.let { memory[key] = it; file.setLastModified(System.currentTimeMillis()); return it to false }
            file.delete()
        }
        val now = System.currentTimeMillis()
        if (!networkAllowed) return null
        if (now - (failedAt[key] ?: 0L) < 60_000) return null
        val connection = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${key.zoom}/${key.x}/${key.y}.png")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "EBikeTerrain/1.0")
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { it.readBytesLimited(1024 * 1024) }
            val decoded = decode(bytes) ?: error("Invalid terrain tile")
            val partial = File(cache, "${key.fileName}.part")
            partial.writeBytes(bytes)
            check(partial.renameTo(file))
            memory[key] = decoded
            failedAt.remove(key)
            return decoded to true
        } catch (_: Exception) {
            if (failedAt.size > 64) failedAt.clear()
            failedAt[key] = now
            return null
        } finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): FloatArray? {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            if (bitmap.width != 256 || bitmap.height != 256) return null
            val colors = IntArray(65536)
            bitmap.getPixels(colors, 0, 256, 0, 0, 256, 256)
            return FloatArray(colors.size) { TerrainCoordinates.elevation(colors[it]) }
        } finally { bitmap.recycle() }
    }

    private fun pruneCache() {
        val files = cache.listFiles { f -> f.extension == "png" }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }
}
