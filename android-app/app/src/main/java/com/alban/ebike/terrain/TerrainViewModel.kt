package com.alban.ebike.terrain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alban.ebike.scene.GeoFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.*

/** Acquisition survives rotation; zoom only changes render detail, never the source sample density. */
class TerrainViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableDownloadedBytes = MutableStateFlow(0L)
    val downloadedBytes = mutableDownloadedBytes.asStateFlow()
    private val receiveBytes: (Int) -> Unit = { count -> mutableDownloadedBytes.update { it + count } }
    private val cacheStorage = MapCacheStorage(application.filesDir, application.cacheDir)
    private val mutableCacheBytes = MutableStateFlow(0L)
    val cacheBytes = mutableCacheBytes.asStateFlow()
    private val mutableCacheClearing = MutableStateFlow(false)
    val cacheClearing = mutableCacheClearing.asStateFlow()
    @Volatile private var cachePaused = false
    @Volatile private var resumeAfterClear = false
    val diagnostics = LoadDiagnostics()
    val geometryCache = GeometryCache(File(application.filesDir, "geometry-v1"))
    private val ign = IgnTerrainSource(application, receiveBytes)
    private val fallback = TerrainRepository(application, receiveBytes)
    private val objects = MapFeatureRepository(application, receiveBytes)
    private val store = ElevationTileStore(File(application.filesDir, "elevation-tiles-v1"))
    private val mutableTerrain = MutableStateFlow<TerrainGrid?>(null)
    val terrain = mutableTerrain.asStateFlow()
    private val mutableMap = MutableStateFlow<MapFeatureArea?>(null)
    val mapArea = mutableMap.asStateFlow()
    private val cadastre = CadastreRepository(application, receiveBytes)
    private val mutableParcels = MutableStateFlow<List<Parcel>>(emptyList())
    val parcels = mutableParcels.asStateFlow()
    private var parcelJob: Job? = null
    private var parcelKey: ElevationTileKey? = null
    private var parcelRetryAfter = 0L
    private val parcelRequests = SharedTileRequests<ElevationTileKey, List<Parcel>>(viewModelScope) { key ->
        cadastre.load(key) { diagnostics.report("CAD", it) }
    }
    fun requestParcels(lat: Double, lon: Double, enabled: Boolean) {
        if (cachePaused) return
        if (!enabled) { parcelJob?.cancel(); parcelKey = null; mutableParcels.value = emptyList(); return }
        val key = ElevationTiles.key(lat, lon, 1)
        if (parcelKey == key && (parcelJob?.isActive == true || LoadDiagnostics.now() < parcelRetryAfter)) return
        parcelKey = key; parcelJob?.cancel()
        parcelJob = viewModelScope.launch(Dispatchers.IO) {
            diagnostics.begin("CAD", "Cadastre · cache puis IGN")
            val found = LinkedHashMap<String, Parcel>()
            try {
                for (tile in ElevationTiles.neighbours(key)) {
                    parcelRequests.get(tile).forEach { found[it.id] = it }
                    ensureActive(); mutableParcels.value = found.values.toList()
                }
                parcelRetryAfter = LoadDiagnostics.now() + 86_400_000
                diagnostics.report("CAD", "Cadastre · ${found.size} parcelles · zone locale")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                parcelRetryAfter = LoadDiagnostics.now() + 120_000
                diagnostics.report("CAD", "Cadastre indisponible / partiel · réessai 2 min")
            } finally { diagnostics.end("CAD") }
        }
    }
    private data class Request(val region: ElevationRegion, val focus: ElevationTileKey)
    private var requested: Request? = null
    private var terrainJob: Job? = null
    private var mapJob: Job? = null
    private var mapRegion: ElevationRegion? = null
    private var retryAfter = 0L
    private var mapRetryAfter = 0L
    private val downloads = SharedTileRequests<ElevationTileKey, FloatArray?>(viewModelScope) { key ->
        store.read(key) ?: ign.fetchTile(key) { diagnostics.report("ALT", it) }?.also { heights ->
            // Save before returning to any UI waiter; cancellation of a view cannot lose this tile.
            runCatching { store.write(key, heights) }.onFailure { Log.w("EBikeTerrain", "Tile cache write failed", it) }
        }
    }

    fun request(lat: Double, lon: Double, half: Double, gpsLat: Double, gpsLon: Double) {
        if (cachePaused) return
        val next = Request(ElevationTiles.region(lat, lon, half), ElevationTiles.key(gpsLat, gpsLon))
        if (requested == next && (terrainJob?.isActive == true || LoadDiagnostics.now() < retryAfter)) return
        requested = next
        terrainJob?.cancel() // A pending per-tile request continues in viewModelScope.
        terrainJob = viewModelScope.launch(Dispatchers.IO) { load(next) }
    }

    private suspend fun load(request: Request) {
        val region = request.region
        val (lat, lon) = region.center
        val near = ElevationTiles.neighbours(request.focus)
        val coarse = region.coarseKeys()
        val order = (listOf(request.focus) + coarse + near).distinct()
        val loaded = LinkedHashMap<ElevationTileKey, FloatArray>()
        diagnostics.begin("ALT", "Tuiles · recherche cache")
        var cached = 0; var fetched = 0; var missing = 0
        loaded.putAll(store.cachedFineWithin(region))
        cached = loaded.size
        for (key in order) if (key !in loaded) store.read(key)?.let { loaded[key] = it; cached++ }
        val legacy = ign.findCoveringCache(lat, lon, region.groundHalf)
        var background = legacy?.let { TerrainGrid(it.originLat, it.originLon, it.halfSizeM, it.size,
            it.heights, "IGN · ancien cache", true) }
        suspend fun publish() {
            currentCoroutineContext().ensureActive()
            val grid = assemble(region, loaded, background,
                "IGN PROGRESSIF · $cached CACHE / $fetched ACQUISES · ${order.count { it in loaded }}/${order.size} TUILES")
            // Never replace usable terrain with an empty intermediate grid.
            if (grid.real || mutableTerrain.value == null) mutableTerrain.value = grid
            val focusCenter = ElevationTiles.coordinate((request.focus.x + .5) * request.focus.side,
                (request.focus.y + .5) * request.focus.side)
            val localRegion = ElevationTiles.region(focusCenter.first, focusCenter.second, 900.0)
            requestObjects(localRegion, TerrainGrid(focusCenter.first, focusCenter.second, 900.0, 2,
                FloatArray(4), "", true))
        }
        publish()
        for (key in order) {
            currentCoroutineContext().ensureActive()
            if (loaded.containsKey(key)) continue
            // Import suitable old grids locally rather than redownload their samples.
            val points = key.coordinates()
            val center = ElevationTiles.coordinate((key.x + .5) * key.side, (key.y + .5) * key.side)
            val old = ign.findCoveringCache(center.first, center.second,
                key.side / 2 * cos(Math.toRadians(center.first)) * 1.01)
            val imported = old?.takeIf { it.halfSizeM * 2 / (it.size - 1) <=
                key.side / 32 * cos(Math.toRadians(center.first)) * 1.05 }?.let {
                val grid = TerrainGrid(it.originLat, it.originLon, it.halfSizeM, it.size, it.heights, "", true)
                FloatArray(1089) { i ->
                    val p = GeoFrame.local(points[i].first, points[i].second, grid.originLat, grid.originLon)
                    grid.sample(p.east, p.north)?.toFloat() ?: Float.NaN
                }.takeIf { values -> values.all { it.isFinite() } }
            }
            val values = if (imported != null) {
                runCatching { store.write(key, imported) }; cached++; imported
            } else downloads.get(key)?.also { fetched++ }
            currentCoroutineContext().ensureActive()
            if (values != null) { loaded[key] = values; publish() } else missing++
            // First nearby tile is visible before any potentially slow fallback/background request.
            if (key == request.focus && values?.any { it.isFinite() } != true && background == null) {
                background = fallback.loadMapzen(lat, lon, region.groundHalf) { diagnostics.report("ALT", it) }
                publish()
            }
        }
        if (loaded.values.any { values -> values.any { !it.isFinite() } } && background == null) {
            background = fallback.loadMapzen(lat, lon, region.groundHalf) { diagnostics.report("ALT", it) }
            publish()
        }
        diagnostics.report("ALT", "Tuiles · $cached cache, $fetched acquises, $missing indisponibles")
        Log.i("EBikeTerrain", "Streaming ready: cache=$cached acquired=$fetched missing=$missing")
        diagnostics.end("ALT")
        retryAfter = LoadDiagnostics.now() + if (missing > 0) 120_000 else 86_400_000
    }

    private fun requestObjects(region: ElevationRegion, grid: TerrainGrid) {
        if (cachePaused) return
        if (mapRegion == region && (mapJob?.isActive == true || LoadDiagnostics.now() < mapRetryAfter)) return
        mapRegion = region
        mapJob?.cancel()
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            do {
            diagnostics.begin("OSM", "Cache objets, indépendant du zoom")
            val area = objects.load(grid) { diagnostics.report("OSM", it) }
            ensureActive()
            if (area != null) mutableMap.value = area
            mapRetryAfter = LoadDiagnostics.now() + if (area?.complete != true) 120_000 else 86_400_000
            diagnostics.end("OSM")
            if (area?.complete == true) break
            delay(120_000)
            } while (isActive)
        }
    }

    private val offlinePreferences = application.getSharedPreferences("offline-status", android.content.Context.MODE_PRIVATE)
    private val mutableOffline = MutableStateFlow(offlinePreferences.getString("status", null)
        ?: "Aucune zone préchargée")
    val offlineStatus = mutableOffline.asStateFlow()
    private val mutableOfflineRunning = MutableStateFlow(false)
    val offlineRunning = mutableOfflineRunning.asStateFlow()
    private var offlineJob: Job? = null
    fun cancelOffline() { offlineJob?.cancel() }
    fun preload(lat: Double, lon: Double, radiusKm: Int, direction: Int, layers: Map<String, Boolean>) {
        if (offlineJob?.isActive == true || mutableCacheClearing.value) return
        cachePaused = false; geometryCache.enabled = true
        offlineJob = viewModelScope.launch(Dispatchers.IO) {
            mutableOfflineRunning.value = true
            offlinePreferences.edit().putString("status", "Préchargement interrompu avant sa fin · relancer pour compléter").apply()
            store.pinWrites = true; objects.pinWrites = true; cadastre.pinWrites = true
            var done = 0; var missing = 0
            val elevation = OfflineSector.tiles(lat, lon, radiusKm, direction, 1)
            val map = if (listOf("roads", "paths", "water", "buildings").any { layers[it] == true })
                OfflineSector.tiles(lat, lon, radiusKm, direction, 2) else emptyList()
            val parcels = if (layers["parcels"] == true) elevation else emptyList()
            val total = elevation.size + map.size + parcels.size
            val root = getApplication<Application>().filesDir
            fun pinCaches() {
                for (directory in listOf("elevation-tiles-v1", "map-features-v2", "cadastre-pci-v1")) {
                    File(root, directory).listFiles { file -> file.extension in listOf("bin", "json") }
                        .orEmpty().forEach { file ->
                            val pin = File(file.parentFile, file.name + ".pin")
                            if (!pin.exists()) pin.writeText("")
                        }
                }
            }
            suspend fun step(action: suspend () -> Boolean) {
                ensureActive()
                check(root.usableSpace > 128L * 1024 * 1024) { "Espace libre insuffisant (128 Mio réservés)" }
                mutableOffline.value = "Préchargement $done/$total · $missing indisponibles"
                try { if (!action()) missing++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { missing++ }
                done++
            }
            try {
                pinCaches()
                for (key in elevation) step {
                    downloads.get(key)?.all { it.isFinite() } == true && File(root, "elevation-tiles-v1/${key.fileName}").exists()
                }
                for (key in map) step { objects.loadTile(key)?.complete == true }
                for (key in parcels) step {
                    cadastre.load(key, refreshPartial = true) {}
                    val file = File(root, "cadastre-pci-v1/${key.level}-${key.x}-${key.y}.json")
                    file.exists() && !org.json.JSONObject(file.readText()).optBoolean("partial")
                }
                mutableOffline.value = if (missing == 0) "Zone prête hors ligne · $done éléments conservés" else
                    "Zone partielle · $done/$total traités · $missing indisponibles. Relancer pour compléter."
            } catch (cancelled: CancellationException) {
                mutableOffline.value = "Interrompu · $done/$total traités, téléchargements conservés"; throw cancelled
            } catch (error: Exception) { mutableOffline.value = "Arrêt : ${error.message} · $done/$total" }
            finally {
                store.pinWrites = false; objects.pinWrites = false; cadastre.pinWrites = false
                offlinePreferences.edit().putString("status", mutableOffline.value).apply()
                mutableOfflineRunning.value = false
            }
        }
    }

    suspend fun refreshCacheSize() = withContext(Dispatchers.IO) {
        mutableCacheBytes.value = cacheStorage.bytes()
    }
    fun resumeCacheLoads() {
        resumeAfterClear = true
        if (!mutableCacheClearing.value) { cachePaused = false; geometryCache.enabled = true }
    }
    fun clearDownloadedCache() {
        if (mutableCacheClearing.value) return
        cachePaused = true; resumeAfterClear = false; geometryCache.enabled = false
        mutableCacheClearing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jobs = listOfNotNull(offlineJob, terrainJob, mapJob, parcelJob)
                jobs.forEach { it.cancel() }; jobs.joinAll()
                downloads.cancelAll(); parcelRequests.cancelAll()
                store.clearMemory(); objects.clearMemory(); ign.clearMemory(); fallback.clearMemory(); geometryCache.clearMemory()
                mutableTerrain.value = null; mutableMap.value = null; mutableParcels.value = emptyList()
                requested = null; mapRegion = null; parcelKey = null
                retryAfter = 0; mapRetryAfter = 0; parcelRetryAfter = 0
                val complete = cacheStorage.clear()
                refreshCacheSize()
                mutableOffline.value = if (complete) "Cache cartographique effacé" else "Effacement partiel · réessayer"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableOffline.value = "Échec de l’effacement · réessayer" }
            finally {
                offlinePreferences.edit().putString("status", mutableOffline.value).apply()
                mutableCacheClearing.value = false
                if (resumeAfterClear) { cachePaused = false; geometryCache.enabled = true }
            }
        }
    }

    companion object {
        /** Assemble one continuous grid; fine and coarse source tiles share exact edge coordinates. */
        fun assemble(region: ElevationRegion, loaded: Map<ElevationTileKey, FloatArray>, background: TerrainGrid?, status: String): TerrainGrid {
            val (lat, lon) = region.center
            val size = 257
            val levels = loaded.keys.map { it.level }.distinct().sortedDescending()
            val heights = FloatArray(size * size) { index ->
                val east = (index % size / (size - 1.0) * 2 - 1) * region.groundHalf
                val north = (index / size / (size - 1.0) * 2 - 1) * region.groundHalf
                val coordinate = GeoFrame.coordinate(east, north, lat, lon)
                val projected = ElevationTiles.projected(coordinate.first, coordinate.second)
                var elevation = background?.let {
                    val p = GeoFrame.local(coordinate.first, coordinate.second, it.originLat, it.originLon)
                    it.sample(p.east, p.north)
                }
                for (level in levels) {
                    val key = ElevationTiles.key(coordinate.first, coordinate.second, level)
                    val values = loaded[key] ?: continue
                    val value = ElevationTiles.sample(key, values, projected.first, projected.second) ?: continue
                    // Blend at missing-neighbour edges only, so loaded fine neighbours have no seams.
                    val fx = projected.first / key.side - key.x; val fy = projected.second / key.side - key.y
                    var weight = 1.0
                    if (!loaded.containsKey(key.copy(x = key.x - 1))) weight = min(weight, fx * 16)
                    if (!loaded.containsKey(key.copy(x = key.x + 1))) weight = min(weight, (1 - fx) * 16)
                    if (!loaded.containsKey(key.copy(y = key.y - 1))) weight = min(weight, fy * 16)
                    if (!loaded.containsKey(key.copy(y = key.y + 1))) weight = min(weight, (1 - fy) * 16)
                    elevation = elevation?.let { it + (value - it) * weight.coerceIn(0.0, 1.0) } ?: value
                }
                elevation?.toFloat() ?: Float.NaN
            }
            return TerrainGrid(lat, lon, region.groundHalf, size, heights, status, heights.any { it.isFinite() })
        }
    }
}
