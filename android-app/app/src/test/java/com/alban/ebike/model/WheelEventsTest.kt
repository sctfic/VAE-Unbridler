package com.alban.ebike.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WheelEventsTest {
    private fun frame(seq: Int = 1, kind: Int = 1, session: Long = 42, at: Long = 1_000_000,
        wheelAt: Long = at, period: Long = 424800, flags: Int = 9): ByteArray {
        val payload = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(session); putLong(at); putLong(wheelAt); putLong(at - 10000)
            putInt(period.toInt()); putInt(424800); putInt(10)
            putShort(2360); put(flags.toByte()); put(1)
        }.array()
        return ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(3); put(kind.toByte()); putShort(seq.toShort()); put(payload)
        }.array()
    }
    private fun decode(packet: ByteArray): BikeTelemetry = WheelEventDecoder().accept(packet)!!
    @Test fun currentEventCarriesIndependentWheelAndMotorPeriods() {
        val packet = frame()
        assertEquals(52, packet.size)
        val p = decode(packet)
        assertEquals(20f, p.wheelSpeedKmh, .001f)
        assertEquals(20f, p.motorSpeedKmh, .001f)
        assertEquals(1_000_000L, p.wheelTimeUs)
        assertTrue(p.motorTimeValid)
    }
    @Test fun modeBitsPeriodsAndUnsignedRevolutionsAreIndependent() {
        val packet = frame(flags = 15)
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(36, 283200); putInt(40, 369391); putInt(44, -1)
        }
        val p = decode(packet)
        assertTrue(p.wheelMoving); assertTrue(p.simulatedOutput); assertTrue(p.speedMode)
        assertTrue(p.motorTimeValid)
        assertEquals(30f, p.wheelSpeedKmh, .001f); assertEquals(23f, p.motorSpeedKmh, .001f)
        assertEquals(4294967295L, p.wheelRevolutions)
        val standard = decode(frame(flags = 1))
        assertFalse(standard.simulatedOutput); assertFalse(standard.speedMode); assertFalse(standard.motorTimeValid)
    }
    @Test fun truncatedOversizedUnsupportedOrMalformedEventsAreRejected() {
        val d = WheelEventDecoder(); val p = frame()
        for (length in listOf(0, 20, 48, 51, 53, 64)) assertNull(d.accept(p.copyOf(length)))
        assertNull(d.accept(p.clone().apply { this[0] = 2 }))
        assertNull(d.accept(p.clone().apply { this[1] = 4 }))
        assertNull(d.accept(frame(wheelAt = 2_000_000)))
        assertNull(d.accept(frame(period = 1)))
        assertNull(d.accept(frame(flags = 255)))
    }
    @Test fun sessionSequenceLossDuplicatesDelayedAndReboot() {
        val c = WheelEventClock()
        assertNull(c.accept(decode(frame()), 10000)) // need initial state
        val initial = c.accept(decode(frame(seq = 65534, kind = 2)), 10000)!!
        assertEquals(10000L, initial.mappedEventMs)
        val next = c.accept(decode(frame(seq = 0, at = 1_424_800)), 10500)!!
        assertEquals(10424L, next.mappedWheelMs)
        assertNull(c.accept(decode(frame(seq = 0, at = 1_424_800)), 11000))
        assertNull(c.accept(decode(frame(seq = 65535, at = 1_400_000)), 11000))
        assertNull(c.accept(decode(frame(seq = 1, at = 1_500_000)), 25000)) // stale
        assertNull(c.accept(decode(frame(seq = 1, session = 99, at = 100000)), 11000))
        val reboot = c.accept(decode(frame(seq = 1, kind = 2, session = 99, at = 100000)), 11000)!!
        assertEquals(11000L, reboot.mappedEventMs)
        assertNull(c.accept(decode(frame(seq = 1, kind = 2, session = 42)), 11001))
        c.reset()
        assertNotNull(c.accept(decode(frame(kind = 2)), 12000))
    }
    private fun mapped(period: Long = 424800): BikeTelemetry = decode(frame(period = period)).copy(
        mappedEventMs = 1000, mappedWheelMs = 1000, mappedMotorMs = 990, receivedMs = 1000)
    @Test fun holdsUntilExpectedPulseThenBoundsDecayAndStops() {
        val p = mapped()
        assertEquals(20f, WheelEventEstimate.at(p, 1250).wheel!!, .001f)
        assertEquals(16.992f, WheelEventEstimate.at(p, 1500).wheel!!, .001f)
        assertEquals(0f, WheelEventEstimate.at(p, 2501).wheel!!, 0f)
        assertNull(WheelEventEstimate.at(p, 13001).wheel)
        assertEquals("communication perdue", WheelEventEstimate.at(p, 13001).quality)
        val slow = mapped(8496000)
        assertEquals(1f, WheelEventEstimate.at(slow, 9000).wheel!!, .001f)
        assertEquals(0f, WheelEventEstimate.at(slow.copy(mappedEventMs = 26000), 26489).wheel!!, 0f)
    }
    @Test fun refreshDoesNotDuplicateSamplesAndGpsDoesNotDecayWithWheel() {
        val p = mapped()
        val state = RideUiState(bluetoothReady = true, eventTelemetry = p, wheelSpeedKmh = 20f,
            gpsSpeedKmh = 30f, gpsSpeedValid = true, gpsSpeedTimeMs = 1000)
        val frequent = SpeedDisplayModel(); val sparse = SpeedDisplayModel()
        frequent.update(state, 1000); sparse.update(state, 1000)
        for (now in 1250L..2250L step 250) frequent.update(state, now)
        val a = frequent.update(state, 2500); val b = sparse.update(state, 2500)
        assertEquals(b.wheel, a.wheel)
        assertEquals(30f, a.main!!, .001f); assertEquals("GPS", a.source)
        val stoppedGps = state.copy(gpsSpeedKmh = 0f, gpsSpeedTimeMs = 1500)
        assertEquals("ROUE", frequent.update(stoppedGps, 1500).source)
        val lost = frequent.update(state.copy(gpsSpeedValid = false), 13001)
        assertNull(lost.main)
    }
    @Test fun heartbeatDoesNotReinsertWheelAndPendingMotorIsIndependent() {
        val model = SpeedDisplayModel(); val p = mapped()
        val s = RideUiState(bluetoothReady = true, eventTelemetry = p)
        assertEquals(20f, model.update(s, 1000).wheel!!, .001f)
        val heartbeat = p.copy(eventType = 3, sequence = 2, eventTimeUs = 2_000_000,
            mappedEventMs = 2000, receivedMs = 2000)
        val v = model.update(s.copy(eventTelemetry = heartbeat), 2000)
        assertTrue(v.wheel!! <= 8.4961f)
        assertTrue(TelemetryDisplayValidation.motor(p.copy(wheelIntervalUs = 849600,
            wheelSpeedKmh = 10f))) // retained command can differ from current wheel
    }
    @Test fun newSessionAndClockCorrectionResetDisplayWindow() {
        val model = SpeedDisplayModel(); val p = mapped()
        val state = RideUiState(bluetoothReady = true, eventTelemetry = p)
        model.update(state, 1000)
        val slower = p.copy(session = 99, wheelSpeedKmh = 10f, motorSpeedKmh = 10f,
            wheelIntervalUs = 849600, motorIntervalUs = 849600, mappedEventMs = 1100, mappedWheelMs = 1100)
        assertEquals(10f, model.update(state.copy(eventTelemetry = slower), 1100).wheel!!, .001f)
        val synchronized = slower.copy(mappedEventMs = 1600, mappedWheelMs = 1600)
        assertEquals(10f, model.update(state.copy(eventTelemetry = synchronized), 1600).wheel!!, .001f)
    }
    @Test fun stoppedStateClearsOldWindowBeforeResume() {
        val model = SpeedDisplayModel(); val p = mapped()
        val state = RideUiState(bluetoothReady = true, eventTelemetry = p)
        model.update(state, 1000)
        val stopped = p.copy(wheelMoving = false, wheelIntervalUs = 0, motorIntervalUs = 0,
            wheelSpeedKmh = 0f, motorSpeedKmh = 0f, mappedEventMs = 2501)
        assertEquals(0f, model.update(state.copy(eventTelemetry = stopped), 2501).wheel!!, 0f)
        val resumed = p.copy(wheelTimeUs = 3_000_000, eventTimeUs = 3_000_000,
            mappedEventMs = 3000, mappedWheelMs = 3000, wheelIntervalUs = 849600, wheelSpeedKmh = 10f)
        assertEquals(10f, model.update(state.copy(eventTelemetry = resumed), 3000).wheel!!, .001f)
    }
    @Test fun brakingIsSmoothedUntilAnOverduePulseRequiresThePhysicalBound() {
        val model = SpeedDisplayModel(); val p = mapped()
        val state = RideUiState(bluetoothReady = true, eventTelemetry = p)
        model.update(state, 1000)
        val braking = p.copy(wheelTimeUs = 2_000_000, eventTimeUs = 2_000_000,
            mappedEventMs = 2000, mappedWheelMs = 2000, wheelIntervalUs = 849600, wheelSpeedKmh = 10f)
        val next = state.copy(eventTelemetry = braking)
        assertEquals(18f, model.update(next, 2250).wheel!!, .001f)
        assertTrue(model.update(next, 3000).wheel!! <= 8.4961f)
    }
    @Test fun fasterDeliveryRefinesTheClockWithoutMakingDelayedPacketsFresh() {
        val clock = WheelEventClock()
        clock.accept(decode(frame(seq = 1, kind = 2)), 10000)
        val faster = clock.accept(decode(frame(seq = 2, at = 1_424_800)), 10100)!!
        assertEquals(10100L, faster.mappedEventMs)
        val delayed = clock.accept(decode(frame(seq = 3, at = 1_849_600)), 11000)!!
        assertEquals(10524L, delayed.mappedEventMs)
        assertEquals(11000L, delayed.receivedMs)
    }
}
