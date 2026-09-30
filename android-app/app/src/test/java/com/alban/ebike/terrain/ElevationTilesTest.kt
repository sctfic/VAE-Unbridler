package com.alban.ebike.terrain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ElevationTilesTest {
    @Test fun fixedKeyAndRegionIgnoreSmallGpsDriftAndDisplayMode() {
        val key = ElevationTiles.key(48.0, 2.0)
        val center = ElevationTiles.coordinate((key.x + .5) * key.side, (key.y + .5) * key.side)
        assertEquals(key, ElevationTiles.key(center.first, center.second + .000001))
        assertEquals(ElevationTiles.region(center.first, center.second, 742.5),
            ElevationTiles.region(center.first + .000001, center.second, 742.5))
        assertEquals(1089, key.coordinates().size)
        assertEquals(9, ElevationTiles.neighbours(key).distinct().size)
        assertEquals(key, ElevationTiles.neighbours(key).first())
    }
    @Test fun adjacentTilesShareIdenticalBoundaryCoordinates() {
        val left = ElevationTileKey(0, 521, 23789)
        val right = left.copy(x = left.x + 1)
        val a = left.coordinates(); val b = right.coordinates()
        for (row in 0..32) {
            assertEquals(a[row * 33 + 32].first, b[row * 33].first, 1e-12)
            assertEquals(a[row * 33 + 32].second, b[row * 33].second, 1e-12)
        }
    }
    @Test fun bilinearSamplingAndNoDataArePreserved() {
        val key = ElevationTileKey(0, 0, 0)
        val heights = FloatArray(1089) { (it % 33 + it / 33).toFloat() }
        assertEquals(32.0, ElevationTiles.sample(key, heights, 128.0, 128.0)!!, 1e-6)
        assertNull(ElevationTiles.sample(key, heights, -1.0, 128.0))
        heights[16 * 33 + 16] = Float.NaN
        assertNull(ElevationTiles.sample(key, heights, 128.0, 128.0))
    }
    @Test fun coldStoreReusesTileAndRejectsCorruptFile() {
        val dir = Files.createTempDirectory("fixed-tiles-test").toFile()
        try {
            val key = ElevationTileKey(0, -12, 500)
            val heights = FloatArray(1089) { 100f }
            ElevationTileStore(dir).write(key, heights)
            assertArrayEquals(heights, ElevationTileStore(dir).read(key), 0f)
            File(dir, key.fileName).writeBytes(byteArrayOf(0))
            assertNull(ElevationTileStore(dir).read(key))
        } finally { dir.deleteRecursively() }
    }
    @Test fun coarseCoverageIsBoundedEvenForLongRides() {
        for (half in listOf(742.5, 2000.0, 20000.0, 200000.0)) {
            val region = ElevationTiles.region(48.0, 2.0, half)
            val keys = region.coarseKeys()
            assertTrue(keys.size <= 4)
            for (dx in listOf(-.999, .999)) for (dy in listOf(-.999, .999)) {
                val p = ElevationTiles.coordinate(region.east + dx * region.half, region.north + dy * region.half)
                assertTrue(keys.contains(ElevationTiles.key(p.first, p.second, keys.first().level)))
            }
        }
    }
    @Test fun sharedDownloadSurvivesCancelledScreenAndIsNotDuplicated() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var count = 0
        val requests = SharedTileRequests<String, Int>(owner) {
            count++; started.complete(Unit); finish.await(); 42
        }
        try {
            val firstScreen = async { requests.get("same-tile") }
            started.await()
            firstScreen.cancelAndJoin()
            val rotatedScreen = async { requests.get("same-tile") }
            yield(); finish.complete(Unit)
            assertEquals(42, rotatedScreen.await())
            assertEquals(1, count)
        } finally { owner.cancel() }
    }
    @Test fun realCoverageDoesNotRequirePrefetchMargin() {
        assertTrue(IgnElevationPolicy.contains(48.0, 2.0, 742.5, 48.0, 2.0, 742.5, 1.0))
    }
}
