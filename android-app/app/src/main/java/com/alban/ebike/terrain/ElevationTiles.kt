package com.alban.ebike.terrain

import java.io.*
import kotlin.math.*

/** Fixed Web Mercator grid. 256 projected metres, 33 samples: <=8 ground metres. */
data class ElevationTileKey(val level: Int, val x: Int, val y: Int) {
    val side: Double get() = 256.0 * 4.0.pow(level)
    val fileName get() = "ign-v1-$level-$x-$y.bin"
    fun coordinates(): List<Pair<Double, Double>> = List(33 * 33) { i ->
        ElevationTiles.coordinate((x + (i % 33) / 32.0) * side, (y + (i / 33) / 32.0) * side)
    }
}

data class ElevationRegion(val x: Int, val y: Int, val radius: Int) {
    val east get() = (x + .5) * 256
    val north get() = (y + .5) * 256
    val half get() = radius * 256.0
    val center get() = ElevationTiles.coordinate(east, north)
    val groundHalf get() = half * cos(Math.toRadians(center.first))
    fun coarseKeys(): List<ElevationTileKey> {
        var level = 1
        while (level < 8) {
            val side = 256.0 * 4.0.pow(level)
            val columns = floor((east + half) / side) - floor((east - half) / side) + 1
            val rows = floor((north + half) / side) - floor((north - half) / side) + 1
            if (columns * rows <= 4) break
            level++
        }
        val side = 256.0 * 4.0.pow(level)
        return (floor((east - half) / side).toInt()..floor((east + half) / side).toInt()).flatMap { tx ->
            (floor((north - half) / side).toInt()..floor((north + half) / side).toInt()).map { ty -> ElevationTileKey(level, tx, ty) }
        }.sortedBy { hypot((it.x + .5) * side - east, (it.y + .5) * side - north) }
    }
}

object ElevationTiles {
    private const val R = 6378137.0
    fun projected(lat: Double, lon: Double): Pair<Double, Double> =
        R * Math.toRadians(lon) to R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-84.0, 84.0)) / 2))
    fun coordinate(east: Double, north: Double): Pair<Double, Double> =
        Math.toDegrees(2 * atan(exp(north / R)) - PI / 2) to Math.toDegrees(east / R)
    fun key(lat: Double, lon: Double, level: Int = 0): ElevationTileKey {
        val (x, y) = projected(lat, lon); val side = 256.0 * 4.0.pow(level)
        return ElevationTileKey(level, floor(x / side).toInt(), floor(y / side).toInt())
    }
    fun region(lat: Double, lon: Double, half: Double): ElevationRegion {
        val key = key(lat, lon)
        val radius = ceil(half / cos(Math.toRadians(lat.coerceIn(-84.0, 84.0))) / 256).toInt() + 1
        return ElevationRegion(key.x, key.y, radius.coerceAtLeast(1))
    }
    fun neighbours(center: ElevationTileKey): List<ElevationTileKey> =
        (-1..1).flatMap { dx -> (-1..1).map { dy -> center.copy(x = center.x + dx, y = center.y + dy) } }
            .sortedBy { abs(it.x - center.x) + abs(it.y - center.y) }
    fun sample(key: ElevationTileKey, heights: FloatArray, east: Double, north: Double): Double? {
        val x = (east / key.side - key.x) * 32
        val y = (north / key.side - key.y) * 32
        if (x !in -1e-7..32.0000001 || y !in -1e-7..32.0000001) return null
        val xx = x.coerceIn(0.0, 32.0); val yy = y.coerceIn(0.0, 32.0)
        val ix = floor(xx).toInt().coerceAtMost(31); val iy = floor(yy).toInt().coerceAtMost(31)
        val a = heights[iy * 33 + ix]; val b = heights[iy * 33 + ix + 1]
        val c = heights[(iy + 1) * 33 + ix]; val d = heights[(iy + 1) * 33 + ix + 1]
        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) return null
        val dx = xx - ix; val dy = yy - iy
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy
    }
}

/** Whole tiles are atomic, independently reusable and persisted even if a UI waiter goes away. */
class ElevationTileStore(private val directory: File) {
    @Volatile var pinWrites = false
    private val memory = object : LinkedHashMap<ElevationTileKey, FloatArray>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ElevationTileKey, FloatArray>?) = size > 128
    }
    @Synchronized fun clearMemory() { memory.clear() }
    @Synchronized fun read(key: ElevationTileKey): FloatArray? {
        memory[key]?.let { return it }
        val file = File(directory, key.fileName)
        return runCatching {
            require(file.length() == 8 + 1089 * 4L)
            val values = DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == 0x45425431); require(input.readInt() == 1089)
                FloatArray(1089) { input.readFloat().also { require(it.isNaN() || it in -12000f..10000f) } }
            }
            if (values.none { it.isFinite() } && System.currentTimeMillis() - file.lastModified() > 86_400_000) return null
            if (values.any { it.isFinite() }) file.setLastModified(System.currentTimeMillis())
            // Negative cache stays on disk only so its expiry is respected in a long-running process.
            if (values.any { it.isFinite() }) memory[key] = values
            values
        }.getOrNull()
    }
    @Synchronized fun cachedFineWithin(region: ElevationRegion): Map<ElevationTileKey, FloatArray> {
        val pattern = Regex("ign-v1-([01])-(-?\\d+)-(-?\\d+)\\.bin")
        val keys = directory.listFiles { f -> f.extension == "bin" }.orEmpty().mapNotNull { file ->
            pattern.matchEntire(file.name)?.let { match ->
                ElevationTileKey(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }
        }.plus(memory.keys.filter { it.level <= 1 }).distinct().filter { key ->
            abs((key.x + .5) * key.side - region.east) <= region.half + key.side / 2 &&
                abs((key.y + .5) * key.side - region.north) <= region.half + key.side / 2
        }.sortedBy { hypot((it.x + .5) * it.side - region.east, (it.y + .5) * it.side - region.north) }
        return keys.take(512).mapNotNull { key -> read(key)?.let { key to it } }.toMap()
    }
    @Synchronized fun write(key: ElevationTileKey, heights: FloatArray) {
        require(heights.size == 1089)
        check(directory.isDirectory || directory.mkdirs())
        val part = File.createTempFile("tile-", ".part", directory)
        try {
            DataOutputStream(part.outputStream().buffered()).use { output ->
                output.writeInt(0x45425431); output.writeInt(1089); heights.forEach(output::writeFloat)
            }
            check(part.renameTo(File(directory, key.fileName)))
            if (pinWrites) File(directory, key.fileName + ".pin").writeText("")
        } finally { part.delete() }
        if (heights.any { it.isFinite() }) memory[key] = heights
        val files = directory.listFiles { f -> f.extension == "bin" }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= 64L * 1024 * 1024) break
            if (File(directory, file.name + ".pin").exists()) continue
            val length = file.length(); if (file.delete()) total -= length
        }
    }
}
