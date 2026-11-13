package com.alban.ebike.ui

import org.junit.Assert.*
import org.junit.Test

class DisplayContrastTest {
    @Test fun keepsALowLightBoostThenIncreasesLinearly() {
        assertEquals(.18f, DisplayContrast.fromLux(0f), 0f)
        assertEquals((DisplayContrast.fromLux(0f) + DisplayContrast.fromLux(8000f)) / 2,
            DisplayContrast.fromLux(4000f), .0001f)
        assertEquals(1f, DisplayContrast.fromLux(100000f), 0f)
        assertEquals(.18f, DisplayContrast.fromLux(Float.NaN), 0f)
        assertEquals(.18f, DisplayContrast.fromLux(-100f), 0f)
    }
}
