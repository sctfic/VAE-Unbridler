package com.alban.ebike.terrain

import com.alban.ebike.scene.*

data class Parcel(val id: String, val label: String, val rings: List<List<MapCoordinate>>)
data class ParcelMesh(val lines: FloatArray = floatArrayOf(), val labels: List<WorldLabel> = emptyList())
data class WorldLabel(val text: String, val point: WorldPoint)
data class ScreenLabel(val text: String, val x: Float, val y: Float)

object ParcelGeometry {
    /** Widest interior scanline segment. Handles concave outlines and holes via even/odd pairing. */
    fun interior(rings: List<List<MapCoordinate>>): MapCoordinate? {
        val outline = rings.firstOrNull()?.takeIf { it.size >= 3 } ?: return null
        val low = outline.minOf { it.latitude }; val high = outline.maxOf { it.latitude }
        var best: MapCoordinate? = null; var width = 0.0
        for (step in 1..19) {
            val y = low + (high - low) * step / 20
            val cuts = rings.flatMap { ring -> ring.zipWithNext().mapNotNull { (a, b) ->
                if ((a.latitude <= y && b.latitude > y) || (b.latitude <= y && a.latitude > y))
                    a.longitude + (y - a.latitude) * (b.longitude - a.longitude) / (b.latitude - a.latitude)
                else null
            } }.sorted()
            for (i in 0 until cuts.size - 1 step 2) if (cuts[i + 1] - cuts[i] > width) {
                width = cuts[i + 1] - cuts[i]; best = MapCoordinate(y, (cuts[i] + cuts[i + 1]) / 2)
            }
        }
        return best
    }
    fun build(parcels: List<Parcel>, terrain: TerrainGrid?): ParcelMesh {
        if (terrain == null || !terrain.real) return ParcelMesh()
        val features = parcels.flatMap { parcel -> parcel.rings.map { MapFeature(false, false, it) } }
        val lines = MapFeatureProjection.build(MapFeatureArea(terrain.originLat, terrain.originLon,
            terrain.halfSizeM, true, features), terrain, TerrainDetail.CLOSE).roads
        val base = terrain.heights.filter { it.isFinite() }.minOrNull()?.toDouble() ?: return ParcelMesh()
        val labels = parcels.mapNotNull { parcel ->
            val coordinate = interior(parcel.rings) ?: return@mapNotNull null
            val p = GeoFrame.local(coordinate.latitude, coordinate.longitude, terrain.originLat, terrain.originLon)
            val h = terrain.sampleSmooth(p.east, p.north) ?: return@mapNotNull null
            WorldLabel(parcel.label, p.copy(height = (h - base) * RideSceneMesh.VERTICAL_EXAGGERATION + 1.5))
        }
        return ParcelMesh(lines, labels)
    }
}
