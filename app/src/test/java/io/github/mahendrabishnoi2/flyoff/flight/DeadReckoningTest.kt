package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/** Reference points: see GeodesyTest. Airport coordinates are from OurAirports (public domain). */
class DeadReckoningTest {

    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.639801, -73.7789)
    private val nan = LatLon(-17.755399, 177.443)     // Nadi, Fiji
    private val lax = LatLon(33.942501, -118.407997)
    private val t0 = 1_795_000_000_000L

    /** The great-circle midpoint of LHR-JFK (formulary intermediate point). */
    private val midAtlantic = LatLon(52.214998, -41.307119)

    private fun fix(latLon: LatLon, speed: Double? = 250.0, altitude: Double? = 11_000.0, bearing: Double? = 262.0) =
        Position(latLon, altitude, speed, bearing, t0)

    private fun estimate(lastFix: Position, millisSinceFix: Long, destination: LatLon = jfk) =
        DeadReckoning.estimate(destination, lastFix, millisSinceFix)

    // --- staleness ---

    @Test
    fun `a fix goes stale after 10 seconds`() {
        assertEquals(10_000L, DeadReckoning.STALE_AFTER_MILLIS)
        assertFalse(DeadReckoning.isStale(0))
        assertFalse(DeadReckoning.isStale(9_999))
        assertTrue(DeadReckoning.isStale(10_000))
        assertTrue(DeadReckoning.isStale(DeadReckoning.MAX_ESTIMATE_MILLIS + 1))
        // A negative age means a clock problem, not a drop-out.
        assertFalse(DeadReckoning.isStale(-20_000))
    }

    // --- movement ---

    @Test
    fun `moves speed times time toward the destination`() {
        val remaining = Geodesy.distanceMeters(midAtlantic, jfk)
        // 10 minutes at 250 m/s is 150 km.
        val there = estimate(fix(midAtlantic), 600_000)!!
        assertEquals(150_000.0, Geodesy.distanceMeters(midAtlantic, there.latLon), 1e-3)
        assertEquals(remaining - 150_000.0, Geodesy.distanceMeters(there.latLon, jfk), 1e-3)
        // 1 h 20 min at 180 m/s is 864 km.
        val slower = estimate(fix(midAtlantic, speed = 180.0), 4_800_000)!!
        assertEquals(864_000.0, Geodesy.distanceMeters(midAtlantic, slower.latLon), 1e-3)
    }

    @Test
    fun `no time since the fix is the fix itself`() {
        val now = estimate(fix(midAtlantic), 0)!!
        assertEquals(0.0, Geodesy.distanceMeters(midAtlantic, now.latLon), 1e-6)
        assertTrue(now.estimated)
    }

    @Test
    fun `follows the great circle from the last fix to the destination, not the planned route`() {
        // (55, -30) is 171.8 km right of the LHR-JFK great circle (formulary XTD).
        val offRoute = LatLon(55.0, -30.0)
        for (minutes in listOf(1L, 10L, 60L, 120L, 180L)) {
            val there = estimate(fix(offRoute), minutes * 60_000)!!
            assertEquals("$minutes min", 0.0, Geodesy.crossTrackMeters(offRoute, jfk, there.latLon), 1e-3)
            assertEquals("$minutes min", minutes * 60 * 250.0, Geodesy.alongTrackMeters(offRoute, jfk, there.latLon), 1e-3)
            assertEquals("$minutes min", Geodesy.initialBearingDegrees(there.latLon, jfk), there.bearingDegrees!!, 1e-9)
        }
        // A minute after the drop-out the dot is still well off the planned route: it doesn't jump sideways.
        val oneMinute = estimate(fix(offRoute), 60_000)!!
        assertTrue(Geodesy.crossTrackMeters(lhr, jfk, oneMinute.latLon) > 160_000.0)
    }

    @Test
    fun `stops at the destination and never overshoots`() {
        // 100 km from JFK at 250 m/s: arrives after 400 s.
        val start = Geodesy.destination(jfk, 60.0, 100_000.0)
        var lastLeft = Double.MAX_VALUE
        for (seconds in 0L..2_000L step 10) {
            val there = estimate(fix(start), seconds * 1_000)!!
            val left = Geodesy.distanceMeters(there.latLon, jfk)
            assertTrue("$seconds s: $left m left", left <= lastLeft + 1e-6)
            assertTrue("$seconds s: flew past", Geodesy.distanceMeters(start, there.latLon) <= 100_000.0 + 1e-3)
            lastLeft = left
        }
        for (seconds in listOf(400L, 401L, 2_000L, 10_000L)) {
            val arrived = estimate(fix(start), seconds * 1_000)!!
            assertEquals("$seconds s", 0.0, Geodesy.distanceMeters(arrived.latLon, jfk), 1e-3)
            assertNull("$seconds s", arrived.bearingDegrees)
            assertEquals(250.0, arrived.speedMetersPerSecond!!, 0.0)
        }
    }

    @Test
    fun `near the destination`() {
        // 2 km out at 70 m/s (landing speed).
        val start = Geodesy.destination(jfk, 240.0, 2_000.0)
        val tenSeconds = estimate(fix(start, speed = 70.0), 10_000)!!
        assertEquals(1_300.0, Geodesy.distanceMeters(tenSeconds.latLon, jfk), 1e-3)
        assertEquals(Geodesy.initialBearingDegrees(tenSeconds.latLon, jfk), tenSeconds.bearingDegrees!!, 1e-9)
        val minute = estimate(fix(start, speed = 70.0), 60_000)!!
        assertEquals(0.0, Geodesy.distanceMeters(minute.latLon, jfk), 1e-3)
        assertNull(minute.bearingDegrees)
    }

    @Test
    fun `a last fix at the destination stays there`() {
        val there = estimate(fix(jfk), 60_000)!!
        assertEquals(0.0, Geodesy.distanceMeters(there.latLon, jfk), 1e-6)
        assertNull(there.bearingDegrees)
    }

    @Test
    fun `across the antimeridian from Fiji to Los Angeles`() {
        val total = Geodesy.distanceMeters(nan, lax)
        // 1 hour at 250 m/s is 900 km.
        val hour = estimate(fix(nan), 3_600_000, destination = lax)!!
        assertEquals(900_000.0, Geodesy.distanceMeters(nan, hour.latLon), 1e-3)
        assertEquals(total - 900_000.0, Geodesy.distanceMeters(hour.latLon, lax), 1e-3)
        assertEquals(0.0, Geodesy.crossTrackMeters(nan, lax, hour.latLon), 1e-3)

        // A fix just west of 180° heading east: ten minutes later it is just east of it.
        val west = LatLon(-10.0, 179.5)
        val tenMinutes = estimate(fix(west), 600_000, destination = lax)!!
        assertEquals(150_000.0, Geodesy.distanceMeters(west, tenMinutes.latLon), 1e-3)
        assertTrue("${tenMinutes.latLon}", tenMinutes.latLon.longitude in -180.0..-178.0)
        // The same fix written with an unwrapped longitude gives the same place.
        val unwrapped = estimate(fix(LatLon(-10.0, -180.5)), 600_000, destination = lax)!!
        assertEquals(0.0, Geodesy.distanceMeters(tenMinutes.latLon, unwrapped.latLon), 1e-3)
    }

    // --- what is kept ---

    @Test
    fun `the estimate is flagged and keeps the time, altitude and speed`() {
        val last = fix(midAtlantic, speed = 231.5, altitude = 10_668.0)
        val there = estimate(last, 90_500)!!
        assertTrue(there.estimated)
        assertFalse(last.estimated)
        assertEquals(t0 + 90_500, there.timeMillis)
        assertEquals(10_668.0, there.altitudeMeters!!, 0.0)
        assertEquals(231.5, there.speedMetersPerSecond!!, 0.0)
        assertNull(estimate(fix(midAtlantic, altitude = null), 90_500)!!.altitudeMeters)
    }

    @Test
    fun `an earlier estimate can be the last fix`() {
        val first = estimate(fix(midAtlantic), 60_000)!!
        val second = estimate(first, 60_000)!!
        val direct = estimate(fix(midAtlantic), 120_000)!!
        assertEquals(0.0, Geodesy.distanceMeters(second.latLon, direct.latLon), 1e-3)
        assertTrue(second.estimated)
    }

    // --- nothing sensible to show ---

    @Test
    fun `no estimate without a usable speed`() {
        assertNull(estimate(fix(midAtlantic, speed = null), 60_000))
        assertNull(estimate(fix(midAtlantic, speed = 0.0), 60_000))
        assertNull(estimate(fix(midAtlantic, speed = FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS - 0.01), 60_000))
        assertNull(estimate(fix(midAtlantic, speed = -250.0), 60_000))
        assertNull(estimate(fix(midAtlantic, speed = Double.NaN), 60_000))
        assertNull(estimate(fix(midAtlantic, speed = Double.POSITIVE_INFINITY), 60_000))
        assertNotNull(estimate(fix(midAtlantic, speed = FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS), 60_000))
    }

    @Test
    fun `no estimate for a negative age or after 3 hours`() {
        assertEquals(3 * 3_600_000L, DeadReckoning.MAX_ESTIMATE_MILLIS)
        assertNull(estimate(fix(midAtlantic), -1))
        assertNull(estimate(fix(midAtlantic), DeadReckoning.MAX_ESTIMATE_MILLIS + 1))
        assertNull(estimate(fix(midAtlantic), Long.MAX_VALUE))
        assertNotNull(estimate(fix(midAtlantic), DeadReckoning.MAX_ESTIMATE_MILLIS))
    }

    // --- against the flight simulator ---

    @Test
    fun `a drop-out in the cruise of a simulated LHR to JFK flight`() {
        val flight = SimulatedFlight(lhr, jfk)
        val duration = flight.durationSeconds
        val lastFix = flight.positionAt(0.3 * duration, t0)
        val gapMillis = (0.1 * duration * 1000).roundToLong()
        val estimated = DeadReckoning.estimate(jfk, lastFix, gapMillis)!!
        val actual = flight.positionAt(0.4 * duration, t0)
        // Both points are in the cruise, on the LHR-JFK great circle at a steady 250 m/s, so the
        // estimate should land on the simulated position. All that is left is the gap being
        // rounded to whole milliseconds (≤ 0.5 ms × 250 m/s = 0.125 m); 10 m leaves room for that
        // and floating-point error while still catching any real mistake (one second is 250 m).
        val error = Geodesy.distanceMeters(estimated.latLon, actual.latLon)
        assertTrue("$error m", error < 10.0)
        assertEquals(actual.timeMillis.toDouble(), estimated.timeMillis.toDouble(), 1.0)
        assertTrue(estimated.estimated)
    }

    @Test
    fun `a simulated flight with GPS gaps stays close to the real track`() {
        // Fly LHR to JFK in 10 s steps, using dead reckoning from the last fix inside each
        // simulated drop-out, as the app does.
        val flight = SimulatedFlight(lhr, jfk)
        var lastFix: Position? = null
        var estimates = 0
        var worst = 0.0
        var t = 0.0
        while (t < flight.durationSeconds) {
            val actual = flight.positionAt(t, t0)
            if (GpsGaps.inGap(t, flight.durationSeconds)) {
                val shown = DeadReckoning.estimate(jfk, lastFix!!, actual.timeMillis - lastFix.timeMillis)
                assertNotNull("no estimate at $t s", shown)
                assertTrue(shown!!.estimated)
                worst = maxOf(worst, Geodesy.distanceMeters(shown.latLon, actual.latLon))
                estimates++
            } else {
                lastFix = actual
            }
            t += 10.0
        }
        // Both gaps (30-40 % and 65-72 %) lie in the cruise at a steady speed: see the test above.
        assertTrue("worst error $worst m", worst < 10.0)
        // 17 % of the 6.4-hour flight in 10 s steps: about 392 estimates.
        val expected = 0.17 * flight.durationSeconds / 10
        assertEquals(expected, estimates.toDouble(), 2.0)
    }
}
