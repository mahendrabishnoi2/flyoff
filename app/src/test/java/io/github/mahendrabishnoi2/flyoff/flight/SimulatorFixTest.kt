package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorFixTest {

    private val duration = 10_000.0 // gaps at 3,000–4,000 s and 6,500–7,200 s

    @Test
    fun withoutGapsTheFixIsNow() {
        assertEquals(3_500.5, simulatedFixSeconds(3_500.5, duration, gaps = false), 0.0)
        assertEquals(100.0, simulatedFixSeconds(100.0, duration, gaps = true), 0.0)
    }

    @Test
    fun inAGapTheLastFixIsBeforeIt() {
        assertEquals(2_999.0, simulatedFixSeconds(3_000.0, duration, gaps = true), 0.0)
        assertEquals(2_999.0, simulatedFixSeconds(3_999.9, duration, gaps = true), 0.0)
        assertEquals(6_499.0, simulatedFixSeconds(7_000.0, duration, gaps = true), 0.0)
    }

    @Test
    fun fixesResumeAfterTheGap() {
        assertEquals(4_000.0, simulatedFixSeconds(4_000.0, duration, gaps = true), 0.0)
    }

    private val flight = SimulatedFlight(LatLon(51.4706, -0.461941), LatLon(40.639801, -73.7789))
    private val takeoff = 1_767_225_600_000L

    private fun at(fraction: Double, gaps: Boolean): SimulatorFix {
        val timeline = SimulationTimeline(flight.durationSeconds, 300, running = false, elapsedAtAnchor = fraction * flight.durationSeconds, anchorMillis = 0)
        return simulatorFix(flight, timeline, takeoff, clockMillis = 0, wallMillis = 0, gaps = gaps)
    }

    @Test
    fun ageIsInSimulatedTime() {
        val fix = at(0.35, gaps = true)
        // 5 % of a ~6.4 h flight since the gap began, about 19 minutes.
        val gapSoFar = 0.05 * flight.durationSeconds * 1000
        assertEquals(gapSoFar, fix.millisSinceFix.toDouble(), 1_500.0)
        assertTrue(DeadReckoning.isStale(fix.millisSinceFix))
        // The last fix is from just before the gap, 30 % into the flight.
        val gapStart = takeoff + 0.30 * flight.durationSeconds * 1000
        assertEquals(gapStart, fix.position.timeMillis.toDouble(), 1_000.0)
        // The estimate takes over, at the simulated time now.
        val estimate = DeadReckoning.estimate(flight.destination, fix.position, fix.millisSinceFix)
        assertNotNull(estimate)
        assertEquals(takeoff + 0.35 * flight.durationSeconds * 1000, estimate!!.timeMillis.toDouble(), 2.0)
    }

    @Test
    fun noGapNoAge() {
        assertEquals(0L, at(0.35, gaps = false).millisSinceFix)
        assertFalse(DeadReckoning.isStale(at(0.5, gaps = true).millisSinceFix))
    }

    @Test
    fun parkedBeforeTakeOff() {
        val timeline = SimulationTimeline.ready(flight.durationSeconds, 300, 0)
        val fix = simulatorFix(flight, timeline, null, clockMillis = 0, wallMillis = 42, gaps = true)
        assertEquals(flight.origin, fix.position.latLon)
        assertEquals(0L, fix.millisSinceFix)
    }
}
