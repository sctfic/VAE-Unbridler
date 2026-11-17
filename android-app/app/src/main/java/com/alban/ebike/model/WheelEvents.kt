package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed four-fragment protocol; incomplete/duplicated fragments never become measurements. */
class WheelEventDecoder {
    private var seq = -1
    private var type = 0
    private var next = 0
    private var startMs = 0L
    private val data = ByteArray(64)
    fun reset() { seq = -1; next = 0 }
    fun accept(bytes: ByteArray, nowMs: Long): BikeTelemetry? {
        if (bytes.size != 20 || bytes[0].toInt() != 2) return null
        val part = bytes[1].toInt() and 15
        val kind = (bytes[1].toInt() and 255) ushr 4
        val number = (bytes[2].toInt() and 255) or ((bytes[3].toInt() and 255) shl 8)
        if (kind !in 1..3 || part !in 0..3) return null
        if (part == 0) { seq = number; type = kind; next = 0; startMs = nowMs }
        if (seq != number || type != kind || part != next || nowMs - startMs > 1000) { reset(); return null }
        bytes.copyInto(data, part * 16, 4, 20)
        next++
        if (next != 4) return null
        reset()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val session = b.long
        val at = b.long; val wheelAt = b.long; val motorAt = b.long
        fun u32() = b.int.toLong() and 0xffff_ffffL
        val wheel = u32(); val motor = u32(); val revolutions = u32(); val emitted = u32()
        val rejected = u32(); val dropped = u32(); val circumference = b.short.toInt() and 65535
        val flags = b.get().toInt() and 255; val confirmation = b.get().toInt() and 255; val lost = u32()
        if (at < 0 || wheelAt < 0 || motorAt < 0 || wheelAt > at ||
            circumference !in 1000..4000 || confirmation !in 0..2 || flags and 0xe0 != 0 ||
            (kind == 1 && (wheelAt != at || wheel == 0L))) return null
        val wheelSpeed = if (wheel == 0L) 0f else (circumference * 3600.0 / wheel).toFloat()
        val motorSpeed = if (motor == 0L) 0f else (circumference * 3600.0 / motor).toFloat()
        if (wheelSpeed !in 0f..99f || motorSpeed !in 0f..99f) return null
        return BikeTelemetry(eventVersion = 2, eventType = kind, session = session,
            eventTimeUs = at, wheelTimeUs = wheelAt, motorTimeUs = motorAt,
            motorTimeValid = flags and 16 != 0, confirmation = confirmation,
            rejectedEdges = rejected, droppedEdges = dropped, telemetryDrops = lost,
            wheelMoving = flags and 1 != 0, simulatedOutput = flags and 2 != 0,
            speedMode = flags and 4 != 0, modeSupported = flags and 8 != 0,
            sequence = number, uptimeMs = at / 1000, wheelIntervalUs = wheel, motorIntervalUs = motor,
            wheelSpeedKmh = wheelSpeed, motorSpeedKmh = motorSpeed, wheelRevolutions = revolutions,
            emittedPulses = emitted, circumferenceMm = circumference)
    }
}

/** Monotone clock mapping: offsets are established by live state/presence, never reception spacing. */
class WheelEventClock {
    private var session: Long? = null
    private val retired = mutableSetOf<Long>()
    private var sequence = -1
    private var lastUs = -1L
    private var offsetUs = Long.MAX_VALUE
    private var lost = 0L
    fun reset() { session = null; retired.clear(); sequence = -1; lastUs = -1; offsetUs = Long.MAX_VALUE; lost = 0 }
    fun accept(p: BikeTelemetry, receiveMs: Long): BikeTelemetry? {
        if (p.session != session) {
            if (p.session in retired || p.eventType == 1) return null
            session?.let { retired.add(it) }
            session = p.session; sequence = -1; lastUs = -1; offsetUs = Long.MAX_VALUE; lost = 0
        }
        val delta = if (sequence < 0) 1 else (p.sequence - sequence) and 65535
        if (delta !in 1..32767 || p.eventTimeUs < lastUs) return null
        // Once a live state established the session, every real event gives an
        // upper bound on the offset. A delayed packet can only give a larger
        // bound, so taking the minimum never makes that packet look fresher.
        offsetUs = minOf(offsetUs, receiveMs * 1000 - p.eventTimeUs)
        if (offsetUs == Long.MAX_VALUE) return null
        if (receiveMs * 1000 - (p.eventTimeUs + offsetUs) > 12_000_000) return null
        lost += delta - 1
        sequence = p.sequence; lastUs = p.eventTimeUs
        fun mapped(us: Long) = (us + offsetUs) / 1000
        return p.copy(mappedEventMs = mapped(p.eventTimeUs), mappedWheelMs = mapped(p.wheelTimeUs),
            mappedMotorMs = mapped(p.motorTimeUs), receivedMs = receiveMs, lostEvents = lost)
    }
}

data class EventSpeeds(val wheel: Float?, val motor: Float?, val quality: String)
object WheelEventEstimate {
    fun stopMs(p: BikeTelemetry) = maxOf(1500L, p.wheelIntervalUs * 3 / 1000)
    fun at(p: BikeTelemetry, nowMs: Long): EventSpeeds {
        if (nowMs - p.mappedEventMs > 12_000 || nowMs < p.mappedEventMs)
            return EventSpeeds(null, null, "communication perdue")
        if (!p.wheelMoving || p.wheelIntervalUs == 0L) return EventSpeeds(0f, 0f, "arrêt")
        val elapsedUs = maxOf(0L, nowMs - p.mappedWheelMs) * 1000
        if (elapsedUs > stopMs(p) * 1000) return EventSpeeds(0f, 0f, "arrêt estimé")
        val wheel = minOf(p.wheelSpeedKmh, (p.circumferenceMm * 3600.0 / maxOf(1L, elapsedUs)).toFloat())
        // The generator keeps its commanded cadence through a pending/rejected wheel
        // event until this stop deadline. Lack of BLE motor reports is not a missing pulse.
        val motor = p.motorSpeedKmh.takeIf { p.motorTimeValid }
        return EventSpeeds(wheel, motor, if (elapsedUs > p.wheelIntervalUs) "estimation" else "mesure")
    }
}
