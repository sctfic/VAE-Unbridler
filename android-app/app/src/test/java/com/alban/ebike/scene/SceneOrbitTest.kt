package com.alban.ebike.scene

import org.junit.Assert.*
import org.junit.Test

class SceneOrbitTest {
    @Test fun pitchAndZoomAreBoundedThenReturnToSlopeAwareAutomaticCamera() {
        val orbit = SceneOrbit()
        orbit.advance(0.0, 0)
        orbit.touch(); orbit.incline(-10.0); orbit.scale(100.0)
        assertEquals(Math.toRadians(20.0), orbit.tilt, .00001)
        assertEquals(4.0, orbit.zoom, 0.0)
        orbit.release(100)
        orbit.advance(0.0, 3099, 10f)
        assertEquals(4.0, orbit.zoom, 0.0)
        for (i in 31..100) orbit.advance(0.0, i * 100L, 10f)
        assertEquals(1.0, orbit.zoom, .001)
        assertEquals(TrackCamera.TILT - kotlin.math.atan(.1) * 1.5, orbit.tilt, .001)
    }
    @Test fun touchAndThreeSecondGraceOverrideAutomaticHeading() {
        val orbit = SceneOrbit()
        orbit.movement(10.0, 0)
        orbit.advance(0.0, 0)
        orbit.touch(); orbit.drag(1.0)
        assertEquals(1.0, orbit.advance(0.0, 500), .00001)
        orbit.release(500)
        assertEquals(1.0, orbit.advance(0.0, 3499), .00001)
        assertTrue(orbit.advance(0.0, 3500) < 1.0)
    }
    @Test fun idleRotatesSlowlyAndSignificantMovementRestoresHeading() {
        val orbit = SceneOrbit()
        orbit.advance(0.0, 0)
        for (i in 1..100) orbit.advance(0.0, i * 100L)
        assertEquals(Math.toRadians(40.0), orbit.heading, .00001)
        orbit.movement(2.0, 10000)
        val before = orbit.heading
        assertTrue(orbit.advance(0.0, 10100) > before)
        orbit.movement(5.0, 10100)
        assertTrue(orbit.advance(0.0, 10200) < before)
    }
    @Test fun newTouchRestartsGraceAndAnglesWrap() {
        val orbit = SceneOrbit()
        orbit.touch(); orbit.drag(Math.PI * 4 + 1)
        orbit.release(0); orbit.touch()
        assertEquals(1.0, orbit.advance(0.0, 9000), .00001)
        orbit.release(9000)
        assertEquals(1.0, orbit.advance(0.0, 11999), .00001)
    }
}
