package com.alban.ebike.terrain

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** IGN PCI Express WFS. Fixed small zones; labels are cadastral references, never owner names. */
class CadastreRepository(context: Context, private val onBytes: (Int) -> Unit = {}) {
    @Volatile var pinWrites = false
    private val cache = File(context.filesDir, "cadastre-pci-v1")
    suspend fun load(key: ElevationTileKey, refreshPartial: Boolean = false, progress: (String) -> Unit): List<Parcel> = withContext(Dispatchers.IO) {
        val file = File(cache, "${key.level}-${key.x}-${key.y}.json")
        if (file.exists()) runCatching {
            check(file.length() <= 8 * 1024 * 1024)
            val json = JSONObject(file.readText())
            check(!refreshPartial || !json.optBoolean("partial"))
            progress(if (json.optBoolean("partial")) "Cadastre · cache partiel (limite de densité)" else "Cadastre · cache disque")
            parse(json.getJSONArray("features"))
        }.getOrNull()?.let { file.setLastModified(System.currentTimeMillis()); return@withContext it }
        val sw = ElevationTiles.coordinate(key.x * key.side, key.y * key.side)
        val ne = ElevationTiles.coordinate((key.x + 1) * key.side, (key.y + 1) * key.side)
        if (!IgnElevationPolicy.mayCover(sw.first, sw.second)) return@withContext emptyList()
        val bbox = "${sw.second},${sw.first},${ne.second},${ne.first},urn:ogc:def:crs:OGC:1.3:CRS84"
        val features = JSONArray()
        var complete = false
        for (page in 0..3) {
            ensureActive()
            progress("Cadastre · IGN page ${page + 1}")
            val connection = URL("https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature" +
                "&TYPENAMES=CADASTRALPARCELS.PARCELLAIRE_EXPRESS:parcelle&OUTPUTFORMAT=application/json" +
                "&SRSNAME=urn:ogc:def:crs:OGC:1.3:CRS84&BBOX=$bbox&COUNT=500&STARTINDEX=${page * 500}&SORTBY=gid")
                .openConnection() as HttpURLConnection
            val response = try {
                connection.connectTimeout = 6000; connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "E-BikeCockpit/0.3.4")
                check(connection.responseCode == 200) { "IGN HTTP ${connection.responseCode}" }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        onBytes(n); check(output.size() + n <= 4 * 1024 * 1024); output.write(buffer, 0, n) }
                    output.toByteArray()
                }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } finally { connection.disconnect() }
            val batch = response.getJSONArray("features")
            for (i in 0 until batch.length()) features.put(batch.getJSONObject(i))
            if (batch.length() < 500 || features.length() >= response.optInt("numberMatched", Int.MAX_VALUE)) { complete = true; break }
            delay(300)
        }
        ensureActive()
        val result = parse(features)
        runCatching {
            check(cache.isDirectory || cache.mkdirs())
            val text = JSONObject().put("features", features).put("partial", !complete).toString()
            check(text.toByteArray().size <= 8 * 1024 * 1024)
            val part = File.createTempFile("parcel-", ".part", cache)
            try { part.writeText(text); check(part.renameTo(file)) } finally { part.delete() }
            if (pinWrites) File(cache, file.name + ".pin").writeText("")
            val files = cache.listFiles { f -> f.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            for (old in files) { if (total <= 64L * 1024 * 1024) break
                    if (File(cache, old.name + ".pin").exists()) continue; val n = old.length(); if (old.delete()) total -= n }
        }
        if (!complete) progress("Cadastre · partiel, limite 2 000 parcelles par zone")
        result
    }
    private fun parse(features: JSONArray): List<Parcel> = buildList {
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i); val properties = feature.getJSONObject("properties")
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.getJSONArray("coordinates")
            val polygons = if (geometry.getString("type") == "MultiPolygon") coordinates else JSONArray().put(coordinates)
            for (p in 0 until polygons.length()) {
                val polygon = polygons.getJSONArray(p)
                val rings = (0 until polygon.length()).map { r ->
                    val ring = polygon.getJSONArray(r)
                    (0 until ring.length()).map { v -> ring.getJSONArray(v).let { MapCoordinate(it.getDouble(1), it.getDouble(0)) } }
                }
                add(Parcel(properties.optString("idu", feature.optString("id")) + ":$p",
                    "${properties.optString("section")} ${properties.optString("numero")}".trim(), rings))
            }
        }
    }
}
