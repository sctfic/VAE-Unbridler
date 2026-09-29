package com.alban.ebike.terrain

import org.junit.Assert.*
import org.junit.Test

class IgnElevationPolicyTest {
    @Test fun cacheResolutionUsesMetresNotOnlyPointCount() {
        assertTrue(IgnElevationPolicy.adequateResolution(750.0, 129, 750.0, 65))
        assertFalse(IgnElevationPolicy.adequateResolution(7500.0, 129, 750.0, 65))
        assertFalse(IgnElevationPolicy.adequateResolution(750.0, 65, 750.0, 129))
        for (detail in TerrainDetail.entries) {
            val points = IgnElevationPolicy.coordinates(48.0, 2.0, 750.0, detail.sourceSize)
            assertEquals(detail.sourceSize * detail.sourceSize, points.size)
            assertEquals(48.0, points[points.size / 2].first, 1e-7)
        }
    }
    @Test fun frenchEnvelopesIncludeMainlandCorsicaAndOverseas() {
        assertTrue(IgnElevationPolicy.mayCover(48.85, 2.35))
        assertTrue(IgnElevationPolicy.mayCover(42.0, 9.0))
        assertTrue(IgnElevationPolicy.mayCover(-21.1, 55.5))
        assertFalse(IgnElevationPolicy.mayCover(40.7, -74.0))
        assertFalse(IgnElevationPolicy.mayCover(Double.NaN, 2.0))
    }
    @Test fun gridMatchesRendererOrientationAndApiLimit() {
        val points = IgnElevationPolicy.coordinates(48.0, 2.0, 550.0)
        assertEquals(16641, points.size)
        val batches = points.chunked(IgnElevationPolicy.BATCH_SIZE)
        assertEquals(4, batches.size)
        assertTrue(batches.all { it.size <= 5000 })
        assertEquals(points, batches.flatten())
        assertEquals(48.0, points[points.size / 2].first, .0000001)
        assertEquals(2.0, points[points.size / 2].second, .0000001)
        assertTrue(points.last().first > points.first().first)
        assertTrue(points[64].second > points.first().second)
    }
    @Test fun noDataAndMalformedElevationsAreNotRenderedAsDeepPits() {
        for (value in listOf(null, -99999.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertTrue(IgnElevationPolicy.elevation(value).isNaN())
        assertEquals(-2f, IgnElevationPolicy.elevation(-2.0), 0f)
        assertEquals(1030.08f, IgnElevationPolicy.elevation(1030.08), .001f)
    }
    @Test fun fallbackOnlyReplacesMissingIgnSamples() {
        val result = IgnElevationPolicy.merge(floatArrayOf(10f, Float.NaN, Float.NaN), floatArrayOf(20f, 30f, Float.NaN))
        assertEquals(10f, result[0], 0f)
        assertEquals(30f, result[1], 0f)
        assertTrue(result[2].isNaN())
    }
    @Test fun cachedGridIsReusedOnlyWhileItFullyCoversVisibleArea() {
        assertTrue(IgnElevationPolicy.contains(48.0, 2.0, 1000.0, 48.0, 2.0, 900.0))
        val shifted = com.alban.ebike.scene.GeoFrame.coordinate(40.0, 0.0, 48.0, 2.0)
        assertTrue(IgnElevationPolicy.contains(48.0, 2.0, 1000.0, shifted.first, shifted.second, 900.0))
        val outside = com.alban.ebike.scene.GeoFrame.coordinate(80.0, 0.0, 48.0, 2.0)
        assertFalse(IgnElevationPolicy.contains(48.0, 2.0, 1000.0, outside.first, outside.second, 900.0))
        assertFalse(IgnElevationPolicy.contains(48.0, 2.0, 500.0, 48.0, 2.0, 600.0))
    }
}
