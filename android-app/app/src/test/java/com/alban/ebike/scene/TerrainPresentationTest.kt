package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import com.alban.ebike.terrain.TerrainGrid
import com.alban.ebike.terrain.TerrainDetail
import org.junit.Assert.*
import org.junit.Test

class TerrainPresentationTest {
    @Test fun viewDetailBoundsGeometryAndGpsUpdatesReuseTerrain() {
        val size = 129
        val terrain = TerrainGrid(48.0, 2.0, 750.0, size,
            FloatArray(size * size) { (it / size).toFloat() }, "test", true)
        val point = TrackPoint(48.0, 2.0, 64f, 0L, 0.0, true)
        val detailed = RideSceneMesh.build(listOf(point), terrain, false, 0.0, detail = TerrainDetail.CLOSE)
        val next = RideSceneMesh.build(listOf(point.copy(latitude = 48.0001)), terrain, false, .1,
            detail = TerrainDetail.CLOSE)
        assertSame(detailed.surface, next.surface)
        assertSame(detailed.contours, next.contours)
        val overview = RideSceneMesh.build(listOf(point), terrain, false, 0.0, detail = TerrainDetail.OVERVIEW)
        assertEquals(detailed.surface.size / 4, overview.surface.size)
        assertTrue(overview.contours.size < detailed.contours.size)
        assertEquals(detailed.marker[2], overview.marker[2], .001f)
    }
    @Test fun terrainIsUpsampledAndVerticalReliefIsPresentationOnly() {
        val size = 5
        val heights = FloatArray(size * size) { i -> (i / size * 10).toFloat() }
        val terrain = TerrainGrid(48.0, 2.0, 100.0, size, heights, "test", true)
        val point = TrackPoint(48.0, 2.0, 20f, 0L, 0.0, true)
        val mesh = RideSceneMesh.build(listOf(point), terrain, false, 0.0)
        assertEquals((128 * 128 * 2 * 3 * 3), mesh.surface.size)
        // Center terrain altitude is 20 m above base; marker adds a 3 m display lift.
        assertEquals(20.0 * RideSceneMesh.VERTICAL_EXAGGERATION + 3.0,
            mesh.marker[2].toDouble(), .01)
        assertEquals(20f, point.altitudeM, 0f)
    }
}
