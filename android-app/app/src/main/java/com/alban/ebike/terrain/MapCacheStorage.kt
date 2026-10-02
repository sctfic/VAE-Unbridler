package com.alban.ebike.terrain

import java.io.File

/** Only map/elevation data and derived geometry. Ride journals and preferences are outside this list. */
class MapCacheStorage(filesDir: File, cacheDir: File) {
    private val directories = listOf(
        filesDir to "elevation-tiles-v1", filesDir to "map-features-v2", filesDir to "geometry-v1",
        filesDir to "cadastre-pci-v1", filesDir to "terrain-v1",
        filesDir to "terrain-ign-rge1m-v1", cacheDir to "terrain-v1",
        cacheDir to "terrain-ign-rge1m-v1",
    ).map { (root, name) ->
        File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    fun bytes(): Long = directories.sumOf { directory ->
        if (!directory.exists()) 0L else directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    fun clear(): Boolean {
        var complete = true
        for (directory in directories) if (directory.exists() && !directory.deleteRecursively()) complete = false
        return complete
    }
}
