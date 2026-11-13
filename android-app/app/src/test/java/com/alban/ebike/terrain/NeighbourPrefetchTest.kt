package com.alban.ebike.terrain

import org.junit.Assert.*
import org.junit.Test

class NeighbourPrefetchTest {
    @Test fun addsAnEntireRingOutsideTheVisibleArea() {
        val focus = ElevationTileKey(2, 100, 200)
        val visible = ElevationTiles.neighbours(focus)
        val expanded = NeighbourPrefetch.expanded(visible, focus, 1.0, 0.0)
        assertEquals(25, expanded.size)
        assertTrue(expanded.containsAll(visible))
        assertTrue(expanded.contains(focus.copy(x = 102, y = 202)))
        assertEquals(focus, expanded.first())
    }
    @Test fun prioritizesForwardNeighboursWithoutDroppingTheRestOfTheRing() {
        val center = ElevationTileKey(2, 100, 200)
        val east = NeighbourPrefetch.ordered(center, 10.0, 0.0)
        val west = NeighbourPrefetch.ordered(center, -10.0, 0.0)
        assertEquals(9, east.size)
        assertEquals(ElevationTiles.neighbours(center).toSet(), east.toSet())
        assertEquals(east.toSet(), west.toSet())
        assertEquals(center, east.first())
        assertEquals(center.x + 1, east[1].x)
        assertEquals(center.x - 1, west[1].x)
    }
    @Test fun visibleTileCoverageIncludesAllFourCornersAcrossABoundary() {
        val center = ElevationTiles.coordinate(4096.0, 4096.0)
        val grid = TerrainGrid(center.first, center.second, 900.0, 2, FloatArray(4), "", true)
        val keys = NeighbourPrefetch.covering(grid, 2)
        assertEquals(4, keys.size)
        assertEquals(4, keys.toSet().size)
        assertTrue(keys.any { it.x == 0 && it.y == 0 })
        assertTrue(keys.any { it.x == 1 && it.y == 1 })
    }
}
