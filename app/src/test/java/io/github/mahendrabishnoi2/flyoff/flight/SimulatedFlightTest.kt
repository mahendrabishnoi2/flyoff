package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class SimulatedFlightTest {

    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.639801, -73.7789)
    private val t0 = 1_795_000_000_000L

    private val flight = SimulatedFlight(lhr, jfk)

    /** Positions every [stepSeconds] from take-off to touchdown, both included. */
    private fun SimulatedFlight.track(stepSeconds: Double = 60.0): List<Pair<Double, Position>> {
        val times = generateSequence(0.0) { it + stepSeconds }.takeWhile { it < durationSeconds }.toList() + durationSeconds
        return times.map { it to positionAt(it, t0) }
    }

    @Test
    fun `route length is the great-circle distance`() {
        // haversine LHR-JFK: 5,539,651.7 m (see GeodesyTest)
        assertEquals(5_539_651.7, flight.routeLengthMeters, 1.0)
    }

    @Test
    fun `LHR to JFK takes a plausible time at the default speed`() {
        val hours = flight.durationSeconds / 3600
        assertTrue("$hours h", hours in 6.0..8.0)
    }

    @Test
    fun `starts at the origin on the ground`() {
        val start = flight.positionAt(0.0, t0)
        assertEquals(0.0, Geodesy.distanceMeters(lhr, start.latLon), 1e-3)
        assertEquals(0.0, start.altitudeMeters!!, 0.0)
        assertTrue(start.speedMetersPerSecond!! > 0.0)
        assertEquals(t0, start.timeMillis)
        // haversine (atan2 course) LHR to JFK: 287.9369°
        assertEquals(287.9369, start.bearingDegrees!!, 1e-3)
    }

    @Test
    fun `ends at the destination on the ground`() {
        val end = flight.positionAt(flight.durationSeconds, t0)
        assertEquals(0.0, Geodesy.distanceMeters(jfk, end.latLon), 1.0)
        assertEquals(0.0, end.altitudeMeters!!, 1e-6)
        // Touchdown is the end of the flight: stopped, not still rolling at landing speed.
        assertEquals(0.0, end.speedMetersPerSecond!!, 0.0)
        // The final course is the reverse of JFK to LHR's initial course: 51.3525° + 180° (haversine).
        assertEquals(231.3525, end.bearingDegrees!!, 1e-3)
    }

    @Test
    fun `lands at landing speed`() {
        val touchdown = flight.positionAt(flight.durationSeconds - 1e-6, t0)
        assertTrue("${touchdown.speedMetersPerSecond} m/s", touchdown.speedMetersPerSecond!! in 1.0..100.0)
    }

    @Test
    fun `stays at the destination after landing`() {
        val later = flight.positionAt(flight.durationSeconds + 3_600.0, t0)
        assertEquals(0.0, Geodesy.distanceMeters(jfk, later.latLon), 1.0)
        assertEquals(0.0, later.altitudeMeters!!, 0.0)
        assertEquals(0.0, later.speedMetersPerSecond!!, 0.0)
        assertEquals(t0 + ((flight.durationSeconds + 3_600.0) * 1000).roundToLong(), later.timeMillis)
    }

    @Test
    fun `time stamps follow the elapsed time`() {
        assertEquals(t0 + 90_500L, flight.positionAt(90.5, t0).timeMillis)
    }

    @Test
    fun `moves forward along the route and stays on it`() {
        var lastAlong = -1.0
        for ((time, position) in flight.track()) {
            val along = Geodesy.alongTrackMeters(lhr, jfk, position.latLon)
            assertTrue("went back at $time s", along > lastAlong)
            lastAlong = along
            assertEquals("off route at $time s", 0.0, Geodesy.crossTrackMeters(lhr, jfk, position.latLon), 1.0)
        }
        assertEquals(flight.routeLengthMeters, lastAlong, 1.0)
    }

    @Test
    fun `altitude and speed follow a climb, cruise and descent`() {
        val track = flight.track()
        for ((time, position) in track) {
            assertTrue("altitude at $time s", position.altitudeMeters!! in 0.0..11_000.0)
            if (time < flight.durationSeconds) assertTrue("speed at $time s", position.speedMetersPerSecond!! > 0.0)
            assertTrue("speed at $time s", position.speedMetersPerSecond!! <= 250.0)
        }
        val mid = flight.positionAt(flight.durationSeconds / 2, t0)
        assertEquals(11_000.0, mid.altitudeMeters!!, 0.0)
        assertEquals(250.0, mid.speedMetersPerSecond!!, 0.0)
        // Top of climb within the first 150–200 km; top of descent within the last 200–250 km.
        val cruise = track.filter { it.second.altitudeMeters == 11_000.0 }.map { Geodesy.alongTrackMeters(lhr, jfk, it.second.latLon) }
        assertTrue("climb ends at ${cruise.first()}", cruise.first() in 150_000.0..210_000.0)
        val left = flight.routeLengthMeters - cruise.last()
        assertTrue("descent starts $left m out", left in 200_000.0..260_000.0)
    }

    @Test
    fun `altitude climbs then descends without jumps`() {
        val track = flight.track(stepSeconds = 10.0)
        track.zipWithNext().forEach { (a, b) ->
            val climb = (b.second.altitudeMeters!! - a.second.altitudeMeters!!) / (b.first - a.first)
            assertTrue("vertical speed $climb m/s at ${a.first} s", abs(climb) < 25.0)
        }
    }

    @Test
    fun `heading follows the great circle`() {
        val (_, mid) = flight.track()[flight.track().size / 2]
        assertEquals(Geodesy.initialBearingDegrees(mid.latLon, jfk), mid.bearingDegrees!!, 1e-9)
    }

    @Test
    fun `a short route stays sane`() {
        val origin = LatLon(51.0, 0.0)
        val destination = Geodesy.destination(origin, 90.0, 100_000.0)
        val short = SimulatedFlight(origin, destination)
        assertEquals(100_000.0, short.routeLengthMeters, 1e-3)
        val minutes = short.durationSeconds / 60
        assertTrue("$minutes min", minutes in 10.0..40.0)
        val track = short.track(stepSeconds = 5.0)
        val highest = track.maxOf { it.second.altitudeMeters!! }
        assertTrue("peak $highest m", highest in 1_000.0..6_000.0)
        track.filter { it.first < short.durationSeconds }
            .forEach { (time, position) -> assertTrue("speed at $time s", position.speedMetersPerSecond!! > 0.0) }
        assertEquals(0.0, Geodesy.distanceMeters(destination, short.positionAt(short.durationSeconds, t0).latLon), 1.0)
    }

    @Test
    fun `slow cruise speeds still work`() {
        val slow = SimulatedFlight(lhr, jfk, cruiseSpeedMetersPerSecond = 50.0)
        slow.track(stepSeconds = 600.0).forEach { (time, position) ->
            assertTrue("speed at $time s", position.speedMetersPerSecond!! in 0.0..50.0)
        }
        assertEquals(0.0, Geodesy.distanceMeters(jfk, slow.positionAt(slow.durationSeconds, t0).latLon), 1.0)
    }

    @Test
    fun `same origin and destination is an instant arrival`() {
        val nowhere = SimulatedFlight(lhr, lhr)
        assertEquals(0.0, nowhere.durationSeconds, 0.0)
        assertEquals(lhr, nowhere.positionAt(0.0, t0).latLon)
    }

    @Test
    fun `rejects bad arguments`() {
        assertThrows(IllegalArgumentException::class.java) { SimulatedFlight(lhr, jfk, cruiseSpeedMetersPerSecond = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { SimulatedFlight(lhr, jfk, cruiseAltitudeMeters = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { flight.positionAt(-1.0, t0) }
    }
}
