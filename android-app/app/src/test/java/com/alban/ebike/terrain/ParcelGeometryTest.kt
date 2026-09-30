package com.alban.ebike.terrain

import org.junit.Assert.*
import org.junit.Test

class ParcelGeometryTest {
    @Test fun labelIsInsideOutlineAndOutsideHole() {
        val outer = listOf(MapCoordinate(0.0, 0.0), MapCoordinate(0.0, 10.0), MapCoordinate(10.0, 10.0),
            MapCoordinate(10.0, 0.0), MapCoordinate(0.0, 0.0))
        val hole = listOf(MapCoordinate(2.0, 2.0), MapCoordinate(2.0, 8.0), MapCoordinate(8.0, 8.0),
            MapCoordinate(8.0, 2.0), MapCoordinate(2.0, 2.0))
        val p = ParcelGeometry.interior(listOf(outer, hole))!!
        assertTrue(p.latitude in 0.0..10.0 && p.longitude in 0.0..10.0)
        assertFalse(p.latitude > 2 && p.latitude < 8 && p.longitude > 2 && p.longitude < 8)
    }
    @Test fun noGeometryMeansNoLabel() { assertNull(ParcelGeometry.interior(emptyList())) }
}
