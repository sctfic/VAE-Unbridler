package com.alban.ebike.terrain

import com.alban.ebike.scene.GeoFrame
import com.alban.ebike.scene.WorldPoint
import org.junit.Assert.*
import org.junit.Test

class MapFeatureProjectionTest {
    @Test fun pathsAndBuildingsHaveIndependentBuffersAndRemainInOverview() {
        val terrain = TerrainGrid(48.0, 2.0, 100.0, 5, FloatArray(25) { 50f }, "test", true)
        val road = MapFeature(false, false, listOf(MapCoordinate(48.0, 2.0), MapCoordinate(48.0001, 2.0001)))
        val area = MapFeatureArea(48.0, 2.0, 100.0, true,
            listOf(road.copy(path = true), road.copy(building = true)))
        val mesh = MapFeatureProjection.build(area, terrain, TerrainDetail.CLOSE)
        assertTrue(mesh.roads.isEmpty()); assertTrue(mesh.water.isEmpty())
        assertTrue(mesh.paths.isNotEmpty()); assertTrue(mesh.buildings.isNotEmpty())
        val overview = MapFeatureProjection.build(area, terrain, TerrainDetail.OVERVIEW)
        assertTrue(overview.paths.isNotEmpty()); assertTrue(overview.buildings.isNotEmpty())
    }
    @Test fun segmentCrossingTerrainIsClippedAndOutsideSegmentIsRejected() {
        val clipped = MapFeatureProjection.clip(WorldPoint(-200.0, 0.0), WorldPoint(200.0, 0.0), 100.0)!!
        assertEquals(-100.0, clipped.first.east, 1e-6)
        assertEquals(100.0, clipped.second.east, 1e-6)
        assertNull(MapFeatureProjection.clip(WorldPoint(-200.0, 150.0), WorldPoint(200.0, 150.0), 100.0))
    }

    @Test fun waysFollowTerrainAndOverviewKeepsMinorRoads() {
        val terrain = TerrainGrid(48.0, 2.0, 100.0, 5, FloatArray(25) { 50f }, "test", true)
        fun coord(east: Double) = GeoFrame.coordinate(east, 0.0, 48.0, 2.0).let { MapCoordinate(it.first, it.second) }
        val road = MapFeature(false, false, listOf(coord(-200.0), coord(200.0)))
        val river = road.copy(water = true, major = true)
        val area = MapFeatureArea(48.0, 2.0, 250.0, true, listOf(road, river))
        val mesh = MapFeatureProjection.build(area, terrain, TerrainDetail.CLOSE)
        assertTrue(mesh.roads.isNotEmpty()); assertTrue(mesh.water.isNotEmpty())
        for (i in mesh.roads.indices step 3) {
            assertTrue(mesh.roads[i] in -100f..100f)
            assertEquals(1.2f, mesh.roads[i + 2], .001f)
        }
        val overview = MapFeatureProjection.build(area, terrain, TerrainDetail.OVERVIEW)
        assertTrue(overview.roads.isNotEmpty()); assertTrue(overview.water.isNotEmpty())
        for (i in overview.water.indices step 6) {
            assertEquals(3f, overview.water[i + 2], .001f)
            assertTrue(kotlin.math.abs(overview.water[i + 3] - overview.water[i]) <= 20.01f)
        }
        val unavailable = terrain.copy(heights = FloatArray(25) { Float.NaN })
        assertTrue(MapFeatureProjection.build(area, unavailable, TerrainDetail.CLOSE).roads.isEmpty())
    }
}
