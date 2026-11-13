package com.alban.ebike.terrain

import com.alban.ebike.scene.GeoFrame
import com.alban.ebike.data.DistanceAltitudeProfile
import com.alban.ebike.data.ElevationGain
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class RideElevationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun grid() = TerrainGrid(48.0, 2.0, 160.0, 65,
        FloatArray(65 * 65) { 100f + ((it / 65) * 5 - 160) * .1f }, "IGN", true)
    @Test fun physicalTerrainHeightProducesTenPercentGrade() {
        val profile = DistanceAltitudeProfile()
        for (i in 0..9) {
            val coord = GeoFrame.coordinate(0.0, i * 5.0, 48.0, 2.0)
            val altitude = RideElevation.sample(grid(), coord.first, coord.second, 4f)
            assertNotNull(altitude)
            profile.update(i * 5.0, altitude)
        }
        assertEquals(10f, profile.grade(10)!!, .01f)
    }
    @Test fun missingCoarseOrPoorlyLocatedTerrainFallsBack() {
        assertNull(RideElevation.sample(null, 48.0, 2.0, 4f))
        assertNull(RideElevation.sample(grid().copy(halfSizeM = 320.0), 48.0, 2.0, 4f))
        assertNull(RideElevation.sample(grid(), 48.0, 2.0, 15f))
        assertNull(RideElevation.sample(grid(), 49.0, 2.0, 4f))
        assertNull(RideElevation.sample(grid().copy(heights = FloatArray(4225) { Float.NaN }), 48.0, 2.0, 4f))
    }
    @Test fun sourceBoundaryDoesNotCountDatumOffsetAsClimbOrGrade() {
        val gain = ElevationGain()
        val profile = DistanceAltitudeProfile()
        for (i in 0..5) { gain.update(100f, false); profile.update(i * 5.0, 100f) }
        gain.update(150f, true); profile.update(30.0, 150f, true)
        assertEquals(0f, gain.metres, 0f)
        assertNull(profile.grade())
        gain.update(151f, false); profile.update(35.0, 151f)
        gain.update(152f, false); profile.update(40.0, 152f)
        assertEquals(0f, gain.metres, 0f)
        assertEquals(20f, profile.grade()!!, .01f)
    }

    @Test fun horizontalErrorIsConvertedToAltitudeEnvelopeOnAnIncline() {
        assertEquals(.4f, RideElevation.positionUncertainty(grid(), 48.0, 2.0, 4f)!!, .01f)
        assertNull(RideElevation.positionUncertainty(null, 48.0, 2.0, 4f))
    }

    @Test fun measurementGridReusesMapTilesWithoutASeparateDownload() {
        val store = ElevationTileStore(temporary.newFolder())
        assertNull(RideElevation.cachedGrid(store, 48.0, 2.0))
        for (key in ElevationTiles.neighbours(ElevationTiles.key(48.0, 2.0))) {
            val heights = key.coordinates().map { coordinate ->
                (100 + GeoFrame.local(coordinate.first, coordinate.second, 48.0, 2.0).north * .1).toFloat()
            }.toFloatArray()
            store.write(key, heights)
        }
        store.clearMemory()
        val cached = RideElevation.cachedGrid(store, 48.0, 2.0)!!
        assertEquals(100f, RideElevation.sample(cached, 48.0, 2.0, 4f)!!, .02f)
        val north = GeoFrame.coordinate(0.0, 50.0, 48.0, 2.0)
        assertEquals(105f, RideElevation.sample(cached, north.first, north.second, 4f)!!, .02f)
    }
}
