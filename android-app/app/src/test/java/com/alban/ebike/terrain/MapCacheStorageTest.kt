package com.alban.ebike.terrain

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapCacheStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun clearingMapsPreservesRideJournalsAndSettings() {
        val files = temporary.newFolder("files"); val cache = temporary.newFolder("cache")
        val maps = File(files, "elevation-tiles-v1").apply { mkdir() }
        File(maps, "tile.bin").writeBytes(ByteArray(100))
        File(maps, "tile.bin.pin").writeText("")
        val geometry = File(files, "geometry-v1").apply { mkdir() }
        File(geometry, "mesh.bin").writeBytes(ByteArray(30))
        val old = File(cache, "terrain-v1").apply { mkdir() }
        File(old, "tile.png").writeBytes(ByteArray(50))
        val rides = File(files, "ride-journals").apply { mkdir() }
        val journal = File(rides, "synthetic-test.json").apply { writeText("test") }
        val settings = File(files, "settings").apply { writeText("test") }
        val storage = MapCacheStorage(files, cache)
        assertEquals(180L, storage.bytes())
        assertTrue(storage.clear()); assertEquals(0L, storage.bytes())
        assertTrue(journal.exists()); assertTrue(settings.exists())
        assertTrue(storage.clear())
    }
    @Test fun emptyCacheDoesNotCreateDirectories() {
        val files = temporary.newFolder("files"); val cache = temporary.newFolder("cache")
        val storage = MapCacheStorage(files, cache)
        assertEquals(0L, storage.bytes()); assertTrue(storage.clear())
        assertTrue(files.listFiles()!!.isEmpty()); assertTrue(cache.listFiles()!!.isEmpty())
    }
}
