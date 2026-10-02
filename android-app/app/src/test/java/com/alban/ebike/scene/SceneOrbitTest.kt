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
    @Test fun inspectionFreezesAutomaticCameraButAllowsManualPerspective() {
        val orbit = SceneOrbit()
        orbit.advance(0.0, 0)
        orbit.automaticPaused = true
        repeat(100) { orbit.advance(2.0, (it + 1) * 100L, 20f) }
        assertEquals(0.0, orbit.heading, 0.0)
        assertEquals(TrackCamera.TILT, orbit.tilt, 0.0)
        orbit.touch(); orbit.drag(.5); orbit.incline(-.1); orbit.scale(2.0)
        orbit.release(11000)
        orbit.advance(2.0, 15000)
        assertEquals(.5, orbit.heading, .00001)
        assertEquals(TrackCamera.TILT - .1, orbit.tilt, .00001)
        assertEquals(2.0, orbit.zoom, 0.0)
        orbit.automaticPaused = false
        orbit.advance(2.0, 15100)
        assertTrue(orbit.heading > .5)
    }

    @Test fun routeCenterIsBarycenterAndSelectedPointProjectsAwayFromCenter() {
        val points = listOf(WorldPoint(0.0, 0.0), WorldPoint(0.0, 0.0), WorldPoint(90.0, 30.0, 6.0))
        val center = TrackCamera.barycenter(points)
        assertEquals(WorldPoint(30.0, 10.0, 2.0), center)
        val marker = TrackCamera.project(WorldPoint(60.0, 20.0, 4.0), 0.0, 200.0, 1.0)
        assertTrue(marker.x > 0)
        assertTrue(marker.y > 0)
    }

}
