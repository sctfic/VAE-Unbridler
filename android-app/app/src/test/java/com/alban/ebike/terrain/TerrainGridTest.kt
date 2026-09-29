package com.alban.ebike.terrain

import org.junit.Assert.*
import org.junit.Test

class TerrainGridTest {
    @Test fun smoothSamplingPreservesPlaneAndStaysInsideSourceRange() {
        val size = 5
        val heights = FloatArray(size * size) { i -> (i % size + i / size * 2).toFloat() }
        val grid = TerrainGrid(48.0, 2.0, 100.0, size, heights, "test", true)
        assertEquals(6.0, grid.sampleSmooth(0.0, 0.0)!!, .0001)
        for (east in -90..90 step 10) for (north in -90..90 step 10) {
            val value = grid.sampleSmooth(east.toDouble(), north.toDouble())!!
            assertTrue(value in 0.0..12.0)
        }
    }
    @Test fun terrariumDecodesPositiveNegativeAndFractionalAltitudes() {
        assertEquals(0f, TerrainCoordinates.elevation(0xff800000.toInt()), 0f)
        assertEquals(2523.265625f, TerrainCoordinates.elevation(0xff89db44.toInt()), .001f)
        assertEquals(-1f, TerrainCoordinates.elevation(0xff7fff00.toInt()), 0f)
    }
    @Test fun bilinearSamplingHandlesBordersAndMissingData() {
        val grid = TerrainGrid(0.0, 0.0, 10.0, 2, floatArrayOf(0f, 10f, 20f, 30f), "", true)
        assertEquals(15.0, grid.sample(0.0, 0.0)!!, .0001)
        assertEquals(30.0, grid.sample(10.0, 10.0)!!, .0001)
        assertNull(grid.sample(11.0, 0.0))
        assertNull(grid.copy(heights = floatArrayOf(0f, Float.NaN, 0f, 0f)).sample(0.0, 0.0))
    }
    @Test fun tileCoordinatesAreBoundedAtPolesAndWrapLongitude() {
        assertEquals(4096.0, TerrainCoordinates.tile(0.0, 0.0, 13).first, .0001)
        assertEquals(4096.0, TerrainCoordinates.tile(0.0, 0.0, 13).second, .0001)
        assertTrue(TerrainCoordinates.tile(90.0, 180.0, 13).second >= 0)
        assertEquals(TerrainCoordinates.tile(0.0, -180.0, 13), TerrainCoordinates.tile(0.0, 180.0, 13))
    }
}
