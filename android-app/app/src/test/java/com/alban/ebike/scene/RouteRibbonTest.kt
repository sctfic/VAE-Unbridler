package com.alban.ebike.scene

import org.junit.Assert.*
import org.junit.Test

class RouteRibbonTest {
    @Test fun retainsEndpointColorsAndConsistentExtrusionOnBothEnds() {
        val ribbon = RouteRibbon.build(floatArrayOf(0f, 0f, 5f, 10f, 0f, 6f),
            floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f))
        assertEquals(18, ribbon.positions.size)
        assertArrayEquals(floatArrayOf(-1f, 1f, 1f, 1f, 1f, -1f), ribbon.sides, 0f)
        assertArrayEquals(floatArrayOf(10f, 0f, 6f), ribbon.neighbors.take(3).toFloatArray(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 5f), ribbon.neighbors.drop(6).take(3).toFloatArray(), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), ribbon.colors.take(3).toFloatArray(), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), ribbon.colors.drop(6).take(3).toFloatArray(), 0f)
    }

    @Test fun doesNotBridgeDisconnectedSegments() {
        val lines = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 100f, 0f, 0f, 101f, 0f, 0f)
        val ribbon = RouteRibbon.build(lines, FloatArray(lines.size) { 1f })
        assertEquals(12, ribbon.sides.size)
        assertArrayEquals(floatArrayOf(100f, 0f, 0f), ribbon.positions.drop(18).take(3).toFloatArray(), 0f)
        assertEquals(0, RouteRibbon.build(floatArrayOf(), floatArrayOf()).positions.size)
    }
}
