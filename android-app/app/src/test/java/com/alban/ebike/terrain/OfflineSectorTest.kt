package com.alban.ebike.terrain
import org.junit.Assert.*
import org.junit.Test
class OfflineSectorTest {
    @Test fun sectorIncludesOriginAndExtendsInChosenDirection() {
        val tiles = OfflineSector.tiles(0.0, 0.0, 10, 0, 1)
        assertTrue(tiles.contains(ElevationTiles.key(0.0, 0.0, 1)))
        assertTrue(tiles.any { it.y >= 8 })
        assertFalse(tiles.any { it.y < -2 })
        assertEquals(tiles.size, tiles.distinct().size)
    }
    @Test fun allDirectionsAndMaximumRadiusAreBounded() {
        for (direction in 0..7) {
            val tiles = OfflineSector.tiles(48.0, 2.0, 50, direction, 1)
            assertTrue(tiles.size in 1..6000)
            assertTrue(tiles.contains(ElevationTiles.key(48.0, 2.0, 1)))
        }
    }
}
