package com.alban.ebike.scene

import com.alban.ebike.model.TrackPoint
import kotlin.math.*

data class WorldPoint(val east: Double, val north: Double, val height: Double = 0.0)
data class ScreenPoint(val x: Double, val y: Double, val depth: Double)

/** Local tangent approximation for regional cycling routes (not a globe projection). */
object GeoFrame {
    private const val R = 6371000.0
    fun local(latitude: Double, longitude: Double, originLat: Double, originLon: Double): WorldPoint {
        val deltaLon = ((longitude - originLon + 540) % 360) - 180
        return WorldPoint(Math.toRadians(deltaLon) * R * cos(Math.toRadians(originLat)),
            Math.toRadians(latitude - originLat) * R)
    }
    fun coordinate(east: Double, north: Double, originLat: Double, originLon: Double): Pair<Double, Double> =
        (originLat + Math.toDegrees(north / R)) to
            (((originLon + Math.toDegrees(east / (R * cos(Math.toRadians(originLat)))) + 540) % 360) - 180)

    // Heading clockwise from north. Right must be east when heading north.
    fun heading(points: List<TrackPoint>, previous: Double): Double {
        val latest = points.lastOrNull() ?: return previous
        if (latest.segmentStart) return previous
        for (point in points.asReversed().drop(1)) {
            val delta = local(latest.latitude, latest.longitude, point.latitude, point.longitude)
            if (hypot(delta.east, delta.north) >= 10) return atan2(delta.east, delta.north)
            if (point.segmentStart) break
        }
        return previous
    }
    fun angleDelta(from: Double, to: Double) = atan2(sin(to - from), cos(to - from))
}

object TrackCamera {
    const val TILT = 0.8726646259971648 // 50 degrees above the ground
    const val FOCAL = 1.9
    const val TARGET_Y = 1.0 / 3.0
    fun center(current: WorldPoint, barycenter: WorldPoint, window: SceneWindow, moving: Boolean, manual: Boolean) =
        if (window == SceneWindow.ALL && (manual || !moving)) barycenter else current
    fun barycenter(points: List<WorldPoint>): WorldPoint = if (points.isEmpty()) WorldPoint(0.0, 0.0) else
        WorldPoint(points.sumOf { it.east } / points.size, points.sumOf { it.north } / points.size,
            points.sumOf { it.height } / points.size)
    fun project(point: WorldPoint, heading: Double, distance: Double, aspect: Double, tilt: Double = TILT, targetY: Double = 0.0): ScreenPoint {
        val right = point.east * cos(heading) - point.north * sin(heading)
        val forward = point.east * sin(heading) + point.north * cos(heading)
        val vertical = forward * sin(tilt) + point.height * cos(tilt)
        val depth = distance + forward * cos(tilt) - point.height * sin(tilt)
        return ScreenPoint(right * FOCAL / aspect / depth, vertical * FOCAL / depth + targetY, depth)
    }
    fun fit(points: List<WorldPoint>, heading: Double, aspect: Double, tilt: Double = TILT, targetY: Double = 0.0): Double {
        var distance = 180.0
        val s = sin(heading); val c = cos(heading)
        for (point in points) {
            val right = point.east * c - point.north * s
            val forward = point.east * s + point.north * c
            val vertical = forward * sin(tilt) + point.height * cos(tilt)
            val offset = forward * cos(tilt) - point.height * sin(tilt)
            distance = max(distance, maxOf(5.01, abs(right) * FOCAL / aspect.coerceAtLeast(.2) / .80,
                abs(vertical) * FOCAL / (if (targetY == 0.0) .64 else if (vertical >= 0) .82 - targetY else .82 + targetY)) - offset)
        }
        return distance
    }
}

enum class SceneWindow(val label: String, val distanceM: Double?) {
    LAST_500_M("500 M", 500.0), LAST_2_KM("2 KM", 2000.0), ALL("TOUT", null);
    fun next() = entries[(ordinal + 1) % entries.size]
}

object TrackWindow {
    fun select(points: List<TrackPoint>, window: SceneWindow): List<TrackPoint> {
        val distance = window.distanceM ?: return points
        val end = points.lastOrNull()?.distanceM ?: return points
        val firstVisible = points.indexOfFirst { it.distanceM >= end - distance }
        if (firstVisible <= 0) return points
        return points.drop((firstVisible - 1).coerceAtLeast(0)).mapIndexed { index, point ->
            if (index == 0) point.copy(segmentStart = true) else point
        }
    }
}

/** Complete session, with 2 m sampling and collinear simplification preserving corners. */
class VisibleTrack {
    private val points = ArrayDeque<TrackPoint>()
    fun clear() { points.clear() }
    fun add(point: TrackPoint): List<TrackPoint> {
        val last = points.lastOrNull()
        if (last == null || point.segmentStart || point.altitudeSegmentStart || point.distanceM - last.distanceM >= 2 || point.resting || last.resting) {
            if (points.size >= 2 && !point.segmentStart && !points.last().segmentStart &&
                !point.altitudeSegmentStart && !points.last().altitudeSegmentStart &&
                !point.resting && !points.last().resting && !points[points.size - 2].resting) {
                val a = points[points.size - 2]
                val b = points.last()
                val ab = GeoFrame.local(b.latitude, b.longitude, a.latitude, a.longitude)
                val ac = GeoFrame.local(point.latitude, point.longitude, a.latitude, a.longitude)
                val length = hypot(ac.east, ac.north)
                val projection = if (length > 0) (ab.east * ac.east + ab.north * ac.north) / (length * length) else -1.0
                val deviation = if (length > 0) abs(ab.east * ac.north - ab.north * ac.east) / length else 100.0
                fun comparable(x: Float?, y: Float?, tolerance: Float) =
                    if (x == null || y == null) x == y else abs(x - y) <= tolerance
                val preservesMetrics = comparable(a.speedKmh, b.speedKmh, .5f) && comparable(b.speedKmh, point.speedKmh, .5f) &&
                    comparable(a.gradePercent, b.gradePercent, .25f) && comparable(b.gradePercent, point.gradePercent, .25f) &&
                    a.altitudeValid == b.altitudeValid && b.altitudeValid == point.altitudeValid &&
                    comparable(a.altitudeUncertaintyM, b.altitudeUncertaintyM, .2f) && comparable(b.altitudeUncertaintyM, point.altitudeUncertaintyM, .2f)
                if (preservesMetrics && projection in 0.0..1.0 && deviation < .5 && point.distanceM - a.distanceM < 25 &&
                    abs(b.altitudeM - (a.altitudeM + (point.altitudeM - a.altitudeM) * projection)) < 1) points.removeLast()
            }
            points.addLast(point)
        }
        return points.toList()
    }
}
