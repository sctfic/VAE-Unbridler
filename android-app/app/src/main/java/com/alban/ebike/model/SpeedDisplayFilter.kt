package com.alban.ebike.model

/** Display only. Samples are held over time, never weighted by callback count. */
class SpeedDisplayFilter(private val accelerationKmhPerSecond: Float = 15f) {
    private data class Sample(val time: Long, val value: Float)
    private val samples = ArrayDeque<Sample>()
    var reason: String = "absent"
        private set
    fun reset() { samples.clear(); reason = "absent" }

    fun accept(value: Float?, timeMs: Long): Boolean {
        val previous = samples.lastOrNull()
        if (previous != null && timeMs <= previous.time) { reason = "désordonné"; return false }
        if (value == null || !value.isFinite() || value !in 0f..99f) {
            reason = "absent/hors plage"; return false
        }
        if (previous != null && timeMs - previous.time <= 4000 && value > previous.value &&
            value - previous.value > accelerationKmhPerSecond * (timeMs - previous.time) / 1000f + .01f) {
            reason = "accélération"; return false
        }
        if (value == 0f || previous != null && timeMs - previous.time > 4000) samples.clear()
        samples.addLast(Sample(timeMs, value))
        prune(timeMs)
        reason = if (value == 0f) "arrêt" else "valide"
        return true
    }

    private fun prune(nowMs: Long) {
        while (samples.size > 1 && samples.elementAt(1).time <= nowMs - 2000) samples.removeFirst()
    }

    fun value(nowMs: Long): Float? {
        val last = samples.lastOrNull() ?: return null
        if (nowMs - last.time !in 0..2000) return null
        prune(nowMs)
        val start = maxOf(samples.first().time, nowMs - 2000)
        if (start == nowMs) return last.value
        var total = 0.0
        val list = samples.toList()
        for (i in list.indices) {
            val from = maxOf(start, list[i].time)
            val to = minOf(nowMs, list.getOrNull(i + 1)?.time ?: nowMs)
            if (to > from) total += list[i].value * (to - from)
        }
        return (total / (nowMs - start)).toFloat()
    }
}

data class DisplaySpeeds(val main: Float? = null, val source: String = "GPS / ROUE —",
    val gps: Float? = null, val wheel: Float? = null, val motor: Float? = null)

class SpeedDisplayModel {
    private val gps = SpeedDisplayFilter()
    private val wheel = SpeedDisplayFilter()
    private val motor = SpeedDisplayFilter()
    private var gpsTime = -1L
    private var wheelTime = -1L
    private var mode: Boolean? = null

    fun update(state: RideUiState, nowMs: Long): DisplaySpeeds {
        if (!state.bluetoothReady) { wheel.reset(); motor.reset(); wheelTime = -1; mode = null }
        else if (state.wheelTelemetryTimeMs != wheelTime) {
            if (mode != null && mode != state.speedMode) motor.reset()
            mode = state.speedMode
            wheelTime = state.wheelTelemetryTimeMs
            wheel.accept(state.wheelSpeedKmh.takeIf { state.wheelTelemetryCoherent }, wheelTime)
            motor.accept(state.motorSpeedKmh.takeIf { state.motorTelemetryCoherent }, wheelTime)
        }
        if (state.gpsSpeedTimeMs != gpsTime) {
            gpsTime = state.gpsSpeedTimeMs
            gps.accept(state.gpsSpeedKmh.takeIf { state.gpsSpeedValid }, gpsTime)
        }
        val g = gps.value(nowMs)
        val w = wheel.value(nowMs)
        val m = motor.value(nowMs)
        // Selection uses the latest accepted GPS stop, not the slowly changing average.
        val gpsStopped = state.gpsSpeedValid && state.gpsSpeedKmh == 0f
        val source = when {
            w != null && w > 0 && (g == null || gpsStopped) -> "ROUE"
            g != null -> "GPS"
            w != null -> "ROUE"
            else -> "GPS / ROUE —"
        }
        val selected = when (source) { "GPS" -> g; "ROUE" -> w; else -> null }
        // Sources already carry their own independent two-second windows.
        return DisplaySpeeds(selected, source, g, w, m)
    }
}

object TelemetryDisplayValidation {
    private fun periodMatches(speed: Float, periodUs: Long, circumference: Int): Boolean {
        if (!speed.isFinite() || speed !in 0f..99f || circumference !in 1000..4000) return false
        if (speed == 0f && periodUs == 0L) return true
        if (periodUs <= 0L) return false
        return kotlin.math.abs(circumference * 3600.0 / periodUs - speed) <= .03
    }
    fun wheel(packet: BikeTelemetry) = periodMatches(packet.wheelSpeedKmh, packet.wheelIntervalUs, packet.circumferenceMm)
    fun motor(packet: BikeTelemetry): Boolean {
        if (!wheel(packet) || !periodMatches(packet.motorSpeedKmh, packet.motorIntervalUs, packet.circumferenceMm)) return false
        val expected = if (packet.simulatedOutput) 20f + (packet.wheelSpeedKmh * 100).toInt() / 10 / 100f else packet.wheelSpeedKmh
        return kotlin.math.abs(expected - packet.motorSpeedKmh) <= .03f
    }
}
