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

    fun value(nowMs: Long, maximumAgeMs: Long = 2000): Float? {
        val last = samples.lastOrNull() ?: return null
        if (nowMs - last.time !in 0..maximumAgeMs) return null
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
    val gps: Float? = null, val wheel: Float? = null, val motor: Float? = null,
    val wheelState: String = "absent")

class SpeedDisplayModel {
    private val gps = SpeedDisplayFilter()
    private val wheel = SpeedDisplayFilter()
    private val motor = SpeedDisplayFilter()
    private var gpsTime = -1L
    private var mode: Boolean? = null
    private var session: Long? = null
    private var wheelEventUs = -1L
    private var motorEventUs = -1L
    private var motorCommand = -1L
    private var clockOffsetMs: Long? = null

    fun update(state: RideUiState, nowMs: Long): DisplaySpeeds {
        val event = state.eventTelemetry
        var estimate: EventSpeeds? = null
        if (!state.bluetoothReady) {
            wheel.reset(); motor.reset(); mode = null; session = null
            wheelEventUs = -1; motorEventUs = -1; motorCommand = -1; clockOffsetMs = null
        } else if (event != null) {
            val offset = event.mappedEventMs - event.eventTimeUs / 1000
            if (session != event.session || clockOffsetMs?.let { kotlin.math.abs(offset - it) > 250 } == true) {
                wheel.reset(); motor.reset(); session = event.session
                wheelEventUs = -1; motorEventUs = -1; motorCommand = -1
            }
            clockOffsetMs = offset
            if (mode != null && mode != state.speedMode) motor.reset()
            mode = state.speedMode
            if (event.wheelMoving && event.wheelIntervalUs > 0 && event.wheelTimeUs != wheelEventUs) {
                wheelEventUs = event.wheelTimeUs
                wheel.accept(event.wheelSpeedKmh, event.mappedWheelMs)
            }
            if (event.motorTimeValid && event.motorIntervalUs > 0 &&
                (event.motorTimeUs != motorEventUs || event.motorIntervalUs != motorCommand)) {
                motorEventUs = event.motorTimeUs; motorCommand = event.motorIntervalUs
                motor.accept(event.motorSpeedKmh, event.mappedEventMs)
            }
            estimate = WheelEventEstimate.at(event, nowMs)
            if (estimate.wheel == 0f) { wheel.reset(); motor.reset() }
        }
        if (state.gpsSpeedTimeMs != gpsTime) {
            gpsTime = state.gpsSpeedTimeMs
            gps.accept(state.gpsSpeedKmh.takeIf { state.gpsSpeedValid }, gpsTime)
        }
        val g = gps.value(nowMs, 4000).takeIf { state.gpsSpeedValid }
        val w: Float?
        val m: Float?
        if (event != null && state.bluetoothReady) {
            val e = estimate!!
            w = e.wheel?.let { bound ->
                if (bound == 0f) 0f else {
                    val smooth = wheel.value(nowMs, WheelEventEstimate.stopMs(event)) ?: bound
                    if ((nowMs - event.mappedWheelMs) * 1000 > event.wheelIntervalUs) minOf(bound, smooth) else smooth
                }
            }
            m = e.motor?.let { bound -> if (bound == 0f) 0f else
                motor.value(nowMs, WheelEventEstimate.stopMs(event)) ?: bound }
        } else { w = null; m = null }
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
        return DisplaySpeeds(selected, source, g, w, m, estimate?.quality ?: "mesure")
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
    fun motor(packet: BikeTelemetry) = periodMatches(packet.motorSpeedKmh, packet.motorIntervalUs, packet.circumferenceMm)
}
