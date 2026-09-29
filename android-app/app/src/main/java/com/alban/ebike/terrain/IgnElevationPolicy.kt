package com.alban.ebike.terrain

import com.alban.ebike.scene.GeoFrame

/** Coverage envelopes from IGN resource metadata, checked 2026-09-26. Not a coverage guarantee. */
object IgnElevationPolicy {
    const val RESOURCE = "ign_rge_alti_par_territoires"
    const val SIZE = 129
    const val BATCH_SIZE = 4225 // Preserve the proven request size; denser grids use multiple requests.
    private val envelopes = arrayOf(
        doubleArrayOf(-5.142, 41.333, 9.561, 51.090),
        doubleArrayOf(-56.519, 46.749, -56.112, 47.145),
        doubleArrayOf(-63.154, 15.832, -61.001, 18.126),
        doubleArrayOf(-61.230, 14.388, -60.809, 14.879),
        doubleArrayOf(55.216, -21.390, 55.837, -20.871),
        doubleArrayOf(45.018, -13.006, 45.300, -12.636))
    fun mayCover(lat: Double, lon: Double) = envelopes.any { lon in it[0]..it[2] && lat in it[1]..it[3] }
    fun contains(cachedLat: Double, cachedLon: Double, cachedHalfSize: Double,
        requestedLat: Double, requestedLon: Double, requestedHalfSize: Double,
        margin: Double = .96): Boolean {
        if (cachedHalfSize <= 0 || requestedHalfSize <= 0) return false
        val delta = GeoFrame.local(requestedLat, requestedLon, cachedLat, cachedLon)
        val usable = cachedHalfSize * margin
        return kotlin.math.abs(delta.east) + requestedHalfSize <= usable &&
            kotlin.math.abs(delta.north) + requestedHalfSize <= usable
    }
    fun coordinates(lat: Double, lon: Double, halfSize: Double, size: Int = SIZE): List<Pair<Double, Double>> =
        List(size * size) { i -> GeoFrame.coordinate(
            (i % size / (size - 1.0) * 2 - 1) * halfSize,
            (i / size / (size - 1.0) * 2 - 1) * halfSize, lat, lon) }
    fun adequateResolution(cachedHalf: Double, cachedSize: Int, requestedHalf: Double, requestedSize: Int) =
        cachedHalf / (cachedSize - 1) <= requestedHalf / (requestedSize - 1) * 1.05
    fun elevation(value: Double?): Float =
        if (value != null && value.isFinite() && value in -12000.0..10000.0) value.toFloat() else Float.NaN
    fun merge(preferred: FloatArray, fallback: FloatArray): FloatArray {
        require(preferred.size == fallback.size)
        return FloatArray(preferred.size) { if (preferred[it].isFinite()) preferred[it] else fallback[it] }
    }
}
