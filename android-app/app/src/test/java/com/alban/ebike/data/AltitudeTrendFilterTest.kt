package com.alban.ebike.data

import org.junit.Assert.*
import org.junit.Test

class AltitudeTrendFilterTest {
    @Test fun smallDipWithinGpsUncertaintyDoesNotReverseAClimb() {
        val filter = AltitudeTrendFilter()
        var previous = filter.update(232f, 0.0, 2.5f, "GPS", true)!!
        val samples = listOf(233f, 234f, 235f, 235.5f, 234.5f, 234f, 235f, 236f, 238f, 240f)
        for (sample in samples) {
            val height = filter.update(sample, 6.0, 2.5f, "GPS", false)!!
            assertTrue("Small noisy dip became a descent", height >= previous)
            previous = height
        }
    }

    @Test fun sustainedDescentAfterSummitIsPreserved() {
        val filter = AltitudeTrendFilter()
        filter.update(230f, 0.0, 2.5f, "GPS", true)
        repeat(20) { filter.update(240f, 6.0, 2.5f, "GPS", false) }
        val peak = filter.update(240f, 6.0, 2.5f, "GPS", false)!!
        var height = peak
        for (i in 1..20) height = filter.update(240f - i, 6.0, 2.5f, "GPS", false)!!
        assertTrue(height < peak - 10)
    }

    @Test fun stopAndSourceChangesNeverCreateACrossSourceRamp() {
        val filter = AltitudeTrendFilter()
        filter.update(230f, 0.0, 2.5f, "GPS", true)
        assertEquals(230f, filter.update(235f, 0.0, 2.5f, "GPS", false)!!, 0f)
        assertEquals(180f, filter.update(180f, 5.0, .5f, "IGN", true)!!, 0f)
        assertNull(filter.update(null, 5.0, null, null, true))
        assertEquals(190f, filter.update(190f, 5.0, .5f, "IGN", false)!!, 0f)
    }
}
