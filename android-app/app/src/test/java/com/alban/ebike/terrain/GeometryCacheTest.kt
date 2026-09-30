package com.alban.ebike.terrain

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import com.alban.ebike.scene.RideSceneMesh

class GeometryCacheTest {
    @Test fun cachedTerrainMeshMatchesComputedGeometry() {
        val dir = Files.createTempDirectory("terrain-mesh-test").toFile()
        try {
            val terrain = TerrainGrid(48.0, 2.0, 100.0, 5, FloatArray(25) { 50f + it }, "test", true)
            val first = RideSceneMesh.build(emptyList(), terrain, false, 0.0,
                detail = TerrainDetail.OVERVIEW, cache = GeometryCache(dir))
            val reports = ArrayList<String>()
            val second = RideSceneMesh.build(emptyList(), terrain.copy(status = "reopened"), false, 0.0,
                detail = TerrainDetail.OVERVIEW, cache = GeometryCache(dir), progress = reports::add)
            assertTrue(reports.any { it.contains("DISQUE") })
            assertArrayEquals(first.surface, second.surface, 0f)
            assertArrayEquals(first.grid, second.grid, 0f)
            assertArrayEquals(first.contours, second.contours, 0f)
        } finally { dir.deleteRecursively() }
    }
    @Test fun survivesNewInstanceAndRejectsTruncation() {
        val dir = Files.createTempDirectory("geometry-test").toFile()
        try {
            val arrays = listOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(), floatArrayOf(4f, 5f, 6f))
            val cache = GeometryCache(dir)
            cache.write("sample", arrays)
            assertEquals("RAM", cache.read("sample")!!.second)
            val disk = GeometryCache(dir).read("sample")!!
            assertEquals("DISQUE", disk.second)
            arrays.indices.forEach { assertArrayEquals(arrays[it], disk.first[it], 0f) }
            File(dir, "sample.bin").writeBytes(byteArrayOf(1, 2, 3))
            assertNull(GeometryCache(dir).read("sample"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun keyTracksGeometryRatherThanStatusText() {
        val terrain = TerrainGrid(48.0, 2.0, 100.0, 5, FloatArray(25) { 50f }, "network", true)
        val key = GeometryCache.key(terrain, TerrainDetail.CLOSE)
        assertEquals(key, GeometryCache.key(terrain.copy(status = "cache"), TerrainDetail.CLOSE))
        assertNotEquals(key, GeometryCache.key(terrain, TerrainDetail.OVERVIEW))
        assertNotEquals(key, GeometryCache.key(terrain.copy(heights = FloatArray(25) { 51f }), TerrainDetail.CLOSE))
        assertNotEquals(key, GeometryCache.key(terrain.copy(originLat = 48.1), TerrainDetail.CLOSE))
        val road = MapFeature(false, false, listOf(MapCoordinate(48.0, 2.0), MapCoordinate(48.0001, 2.0)))
        val area = MapFeatureArea(48.0, 2.0, 100.0, true, listOf(road))
        assertNotEquals(GeometryCache.key(terrain, TerrainDetail.CLOSE, area),
            GeometryCache.key(terrain, TerrainDetail.CLOSE, area.copy(features = listOf(road.copy(path = true)))))
    }
}
