package com.alban.ebike.model

/** Follow the recorded path between pause boundaries, without bridging GPS discontinuities. */
fun pauseSegments(pauses: List<RidePause>, track: List<TrackPoint>, current: TrackPoint?): List<Pair<TrackPoint, TrackPoint>> =
    pauses.flatMap { pause ->
        val end = pause.end ?: current ?: pause.start
        val points = listOf(pause.start) + track.filter { it.timeMs > pause.start.timeMs && it.timeMs < end.timeMs } + end
        points.zipWithNext().filter { (a, b) -> !b.segmentStart || a == b }
    }

/** Split a route edge exactly at rest boundaries; zero-distance rests have no drawable segment. */
fun restSections(start: Double, end: Double, pauses: List<RidePause>): List<Triple<Float, Float, Boolean>> {
    if (end <= start) return listOf(Triple(0f, 1f, false))
    val cuts = (listOf(start, end) + pauses.flatMap { listOf(it.start.distanceM, it.end?.distanceM ?: end) }
        .filter { it > start && it < end }).distinct().sorted()
    return cuts.zipWithNext().map { (a, b) ->
        val midpoint = (a + b) / 2
        Triple(((a - start) / (end - start)).toFloat(), ((b - start) / (end - start)).toFloat(),
            pauses.any { midpoint >= it.start.distanceM && midpoint < (it.end?.distanceM ?: Double.POSITIVE_INFINITY) })
    }
}
