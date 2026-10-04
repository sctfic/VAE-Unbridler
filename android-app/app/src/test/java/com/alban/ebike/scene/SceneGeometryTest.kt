package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SceneGeometryTest {
    @Test fun headingNorthKeepsEastOnTheRightAndNorthAhead() {
        assertTrue(TrackCamera.project(WorldPoint(10.0, 0.0), 0.0, 100.0, 1.0).x > 0)
        assertTrue(TrackCamera.project(WorldPoint(0.0, 10.0), 0.0, 100.0, 1.0).y > 0)
        assertTrue(TrackCamera.project(WorldPoint(0.0, -10.0), 0.0, 100.0, 1.0).y < 0)
    }
    @Test fun headingEastKeepsSouthOnTheRight() {
        assertTrue(TrackCamera.project(WorldPoint(0.0, -10.0), PI / 2, 100.0, 1.0).x > 0)
        assertTrue(TrackCamera.project(WorldPoint(10.0, 0.0), PI / 2, 100.0, 1.0).y > 0)
    }
    @Test fun cameraFitsLoopsSteepClimbsAndStraightLinesInEveryOrientation() {
        val routes = listOf(
            (0..100).map { WorldPoint(0.0, it * 20.0 - 1000, it * 4.0 - 200) },
            (0..100).map { WorldPoint(cos(it * PI / 50) * 800, sin(it * PI / 50) * 800, sin(it * PI / 25) * 250) })
        for (route in routes) for (aspect in listOf(.45, .9, 2.4)) for (degrees in 0..360 step 15) {
            val heading = Math.toRadians(degrees.toDouble())
            val fit = TrackCamera.fit(route, heading, aspect)
            route.forEach { point ->
                val p = TrackCamera.project(point, heading, fit, aspect)
                assertTrue("x=${p.x}", abs(p.x) <= .801)
                assertTrue("y=${p.y}", abs(p.y) <= .641)
                assertTrue(p.depth > 0)
            }
        }
    }
    @Test fun shiftedCameraAndModePivots() {
        val current = WorldPoint(100.0, 100.0)
        val center = WorldPoint(10.0, 20.0)
        for (window in SceneWindow.entries) {
            assertEquals(current, TrackCamera.center(current, center, window, true, false))
            assertEquals(if (window == SceneWindow.ALL) center else current,
                TrackCamera.center(current, center, window, true, true))
        }
        assertEquals(1.0 / 3.0, TrackCamera.project(WorldPoint(0.0, 0.0), 0.0, 500.0, 1.0,
            targetY = TrackCamera.TARGET_Y).y, .0001)
        val route = listOf(WorldPoint(300.0, 700.0, 80.0), WorldPoint(-300.0, -800.0))
        for (aspect in listOf(.45, 2.4)) for (heading in listOf(0.0, 1.0, 3.0)) {
            val distance = TrackCamera.fit(route, heading, aspect, targetY = TrackCamera.TARGET_Y)
            route.forEach { assertTrue(abs(TrackCamera.project(it, heading, distance, aspect,
                targetY = TrackCamera.TARGET_Y).y) <= .821) }
        }
    }

    @Test fun restSamplesSurviveWithoutDistanceProgress() {
        val history = VisibleTrack()
        val start = TrackPoint(48.0, 2.0, 100f, 0L, 10.0)
        history.add(start)
        repeat(10) { history.add(start.copy(timeMs = it * 1000L + 1000, resting = true)) }
        val result = history.add(start.copy(timeMs = 11000, resting = false))
        assertEquals(12, result.size)
        assertEquals(10, result.count { it.resting })
    }

    @Test fun distanceWindowsChangeFramingWithoutTruncatingTheRoute() {
        val points = (0..40).map { i -> TrackPoint(48.0 + i * .001, 2.0, 100f,
            i * 1000L, i * 100.0, i == 0) }
        val full = RideSceneMesh.build(points, null, false, 0.0)
        for (window in SceneWindow.entries) {
            val mesh = RideSceneMesh.build(points, null, false, 0.0, window = window)
            assertArrayEquals(full.route, mesh.route, 0f)
            assertArrayEquals(full.routeColors, mesh.routeColors, 0f)
            assertEquals(TrackWindow.select(points, window).size, mesh.frame.size)
            assertEquals(full.pathCenter, mesh.pathCenter)
            assertEquals(full.frame.last(), mesh.frame.last())
        }
    }

    @Test fun restDurationAndPositionCannotPullTheBarycenter() {
        val a = TrackPoint(48.0, 2.0, 100f, 0L, 0.0)
        val b = a.copy(latitude = 48.001, timeMs = 1000, distanceM = 100.0)
        val rest = (1..100).map { b.copy(latitude = 48.002, timeMs = 1000L + it * 1000, resting = true) }
        // Same origin/terrain for both scenes, independently of the last rest coordinate.
        val terrain = com.alban.ebike.terrain.TerrainGrid(48.0, 2.0, 1000.0, 5, FloatArray(25), "test", true)
        val moving = RideSceneMesh.build(listOf(a, b), terrain, false, 0.0)
        val paused = RideSceneMesh.build(listOf(a, b) + rest, terrain, false, 0.0)
        assertEquals(moving.pathCenter, paused.pathCenter)
        val onlyRest = RideSceneMesh.build(rest, terrain, false, 0.0)
        assertEquals(onlyRest.frame.last(), onlyRest.pathCenter)
    }

    @Test fun longitudeScaleIsCorrectAndDatelineDoesNotJump() {
        val east = GeoFrame.local(60.0, .001, 60.0, 0.0)
        val north = GeoFrame.local(60.001, 0.0, 60.0, 0.0)
        assertEquals(.5, east.east / north.north, .001)
        assertTrue(abs(GeoFrame.local(0.0, -179.999, 0.0, 179.999).east) < 225)
        assertEquals(Math.toRadians(2.0), GeoFrame.angleDelta(Math.toRadians(359.0), Math.toRadians(1.0)), .00001)
    }
    @Test fun completeTrackPreservesStartCornersAndGpsGaps() {
        val history = VisibleTrack()
        var points = emptyList<TrackPoint>()
        for (i in 0..1600) {
            val coord = GeoFrame.coordinate(if (i < 1000) 0.0 else (i - 1000) * 2.0,
                min(i, 1000) * 2.0, 48.0, 2.0)
            points = history.add(TrackPoint(coord.first, coord.second, 100f, i * 1000L, i * 2.0, i == 0 || i == 1400))
        }
        assertEquals(0.0, points.first().distanceM, 0.0)
        assertTrue(points.any { it.distanceM == 2000.0 })
        assertTrue(points.any { it.distanceM == 2800.0 && it.segmentStart })
        assertTrue(points.size < 300)
        val mesh = RideSceneMesh.build(points, null, false, 0.0)
        val breaks = points.drop(1).count { it.segmentStart }
        assertEquals((points.size - 1 - breaks) * 6, mesh.route.size)
        history.clear()
        assertEquals(1, history.add(points.last().copy(distanceM = 0.0, segmentStart = true)).size)
    }

    @Test fun doesNotDiscardPointsBeyondFormerPointLimit() {
        val history = VisibleTrack()
        var points = emptyList<TrackPoint>()
        for (i in 0..2500) points = history.add(TrackPoint(48.0 + i * .00004,
            2.0 + (i % 2) * .00005, 100f, i * 1000L, i * 6.0, i == 0))
        assertEquals(2501, points.size)
        assertEquals(0.0, points.first().distanceM, 0.0)
    }

    @Test fun sceneWindowsKeepOneBoundaryPointAndBreakTheArtificialJoin() {
        val points = (0..30).map { i -> TrackPoint(48.0 + i * .0001, 2.0, 100f,
            i * 1000L, i * 100.0, i == 0) }
        assertSame(points, TrackWindow.select(points, SceneWindow.ALL))
        val last500 = TrackWindow.select(points, SceneWindow.LAST_500_M)
        assertEquals(7, last500.size)
        assertEquals(2400.0, last500.first().distanceM, 0.0)
        assertTrue(last500.first().segmentStart)
        assertEquals(22, TrackWindow.select(points, SceneWindow.LAST_2_KM).size)
        assertEquals(SceneWindow.LAST_500_M, SceneWindow.ALL.next())
    }
}
