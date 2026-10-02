package com.alban.ebike.model

/** Follow the recorded path between pause boundaries, without bridging GPS discontinuities. */
fun pauseSegments(pauses: List<RidePause>, track: List<TrackPoint>, current: TrackPoint?): List<Pair<TrackPoint, TrackPoint>> =
    pauses.flatMap { pause ->
        val end = pause.end ?: current ?: pause.start
        val points = listOf(pause.start) + track.filter { it.timeMs > pause.start.timeMs && it.timeMs < end.timeMs } + end
        points.zipWithNext().filter { (a, b) -> !b.segmentStart || a == b }
    }
