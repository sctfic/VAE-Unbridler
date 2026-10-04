package com.alban.ebike.data

import kotlin.math.*

data class WheelCalibrationProposal(val circumferenceMm: Int, val distanceM: Double,
    val intervals: Double, val straightness: Double, val endTimeMs: Long) {
    fun differencePercent(currentMm: Int) = (distanceM * 1000 / intervals / currentMm - 1) * 100
    fun significant(currentMm: Int) = currentMm > 0 && abs(differencePercent(currentMm)) > 1.0 + 1e-9
}

/** Session-only history, independent of GPS speed and of the configured wheel circumference. */
class WheelCalibration {
    data class Sample(val timeMs: Long, val latitude: Double, val longitude: Double,
        val accuracyM: Float, val ticks: Double, val segment: Long)
    private data class Tick(val time: Long, val count: Long)
    private data class Fix(val time: Long, val lat: Double, val lon: Double, val accuracy: Float)
    private val ticks = ArrayDeque<Tick>()
    private val pending = ArrayDeque<Fix>()
    private val history = ArrayDeque<Sample>()
    private var segment = 0L
    private var lastUptime: Long? = null
    private var offset: Long? = null
    private var lastGps = -1L
    private var revision = 0L
    private var generation = 0L
    private var analysedRevision = -1L
    private var best: WheelCalibrationProposal? = null

    @Synchronized fun reset() {
        disconnect(); history.clear(); best = null; revision++; generation++
    }
    @Synchronized fun disconnect() {
        ticks.clear(); pending.clear(); lastUptime = null; offset = null; lastGps = -1; segment++
    }
    @Synchronized fun telemetry(receivedMs: Long, uptimeMs: Long, count: Long) {
        val previous = ticks.lastOrNull()
        if (lastUptime?.let { uptimeMs <= it } == true ||
            previous?.let { count < it.count || receivedMs - (it.time + (offset ?: 0)) > 2000 } == true) disconnect()
        // Smallest observed delivery offset removes variable BLE latency. No wall clock is used.
        offset = minOf(offset ?: Long.MAX_VALUE, receivedMs - uptimeMs)
        lastUptime = uptimeMs
        ticks.addLast(Tick(uptimeMs, count))
        while (ticks.size > 100) ticks.removeFirst()
        drain(receivedMs)
    }
    @Synchronized fun gps(timeMs: Long, nowMs: Long, lat: Double, lon: Double, accuracyM: Float, accepted: Boolean) {
        if (timeMs <= lastGps) return
        if (lastGps >= 0 && timeMs - lastGps > 2000) { segment++; pending.clear() }
        lastGps = timeMs
        if (!accepted || nowMs - timeMs !in 0..2000 || !accuracyM.isFinite() || accuracyM !in 0f..MAX_ACCURACY_M ||
            !lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
            segment++; pending.clear(); return
        }
        pending.addLast(Fix(timeMs, lat, lon, accuracyM))
        drain(nowMs)
    }
    private fun drain(now: Long) {
        val clockOffset = offset ?: return
        while (pending.isNotEmpty()) {
            val fix = pending.first()
            val time = fix.time - clockOffset
            if (ticks.lastOrNull()?.time?.let { it < time } != false) {
                if (now - fix.time > 2000) { pending.removeFirst(); segment++; continue }
                return
            }
            pending.removeFirst()
            val before = ticks.lastOrNull { it.time <= time }
            val after = ticks.firstOrNull { it.time >= time }
            if (before == null || after == null || after.time - before.time > 1000 || after.count < before.count) {
                segment++; continue
            }
            val fraction = if (after.time == before.time) 0.0 else (time - before.time).toDouble() / (after.time - before.time)
            val count = before.count + fraction * (after.count - before.count)
            history.addLast(Sample(fix.time, fix.lat, fix.lon, fix.accuracy, count, segment))
            while (history.size > 20000) history.removeFirst()
            revision++
        }
    }
    /** Called on a worker during a pause, only when history changed. */
    fun evaluate(): WheelCalibrationProposal? {
        val snapshot: List<Sample>
        val version: Long
        val epoch: Long
        synchronized(this) {
            if (revision == analysedRevision) return best
            snapshot = history.toList(); version = revision; epoch = generation
        }
        val candidate = analyse(snapshot)
        synchronized(this) {
            if (generation != epoch) return best
            analysedRevision = version
            if (candidate != null && (best == null || candidate.distanceM > best!!.distanceM)) best = candidate
            return best
        }
    }

    companion object {
        const val MIN_DISTANCE_M = 500.0
        const val MIN_STRAIGHTNESS = .95
        const val MAX_ACCURACY_M = 7f
        private fun distance(a: Sample, b: Sample): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(((b.longitude - a.longitude + 540) % 360) - 180)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        fun analyse(samples: List<Sample>): WheelCalibrationProposal? {
            var best: WheelCalibrationProposal? = null
            val run = ArrayList<Sample>()
            val distances = ArrayList<Double>()
            var first = 0
            var previous: Sample? = null
            for (point in samples) {
                val old = previous
                if (old == null || point.segment != old.segment || point.timeMs - old.timeMs !in 1..2000 ||
                    point.ticks <= old.ticks || point.accuracyM !in 0f..MAX_ACCURACY_M || distance(old, point) > 80) {
                    run.clear(); distances.clear(); first = 0
                }
                previous = point
                if (point.accuracyM !in 0f..MAX_ACCURACY_M) continue
                val last = run.lastOrNull()
                // Thin position jitter over a few metres, retaining the measured polyline through bends.
                if (last != null && distance(last, point) < max(5.0, point.accuracyM * 2.0)) continue
                val length = if (last == null) 0.0 else distances.last() + distance(last, point)
                run.add(point); distances.add(length)
                while (first < run.lastIndex && distance(run[first], point) / (length - distances[first]) < MIN_STRAIGHTNESS) first++
                val travelled = length - distances[first]
                val intervals = point.ticks - run[first].ticks
                if (travelled + 1e-6 < MIN_DISTANCE_M || intervals <= 0) continue
                val perimeter = travelled * 1000 / intervals
                if (perimeter !in 1000.0..4000.0) continue
                val ratio = distance(run[first], point) / travelled
                if (best == null || travelled > best.distanceM || travelled == best.distanceM && ratio > best.straightness) {
                    best = WheelCalibrationProposal(perimeter.roundToInt(), travelled, intervals, ratio, point.timeMs)
                }
            }
            return best
        }
    }
}
