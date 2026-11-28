package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedTrackTest {

    private val t0 = 1_767_225_600_000L
    private val flight = SimulatedFlight(LatLon(51.4706, -0.461941), LatLon(49.0097, 2.5479))

    @Test
    fun `one point every 5 simulated seconds from take-off to touchdown`() {
        val points = flight.recordedTrack(t0, gaps = false)
        assertEquals(t0, points.first().timeMillis)
        assertEquals(flight.positionAt(0.0, t0).latLon, points.first().latLon)
        val touchdown = flight.positionAt(flight.durationSeconds, t0)
        assertEquals(touchdown.timeMillis, points.last().timeMillis)
        assertEquals(touchdown.latLon, points.last().latLon)
        assertTrue(points.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis in 1..5_000 })
        assertEquals(kotlin.math.ceil(flight.durationSeconds / 5).toInt() + 1, points.size)
    }

    @Test
    fun `the GPS gaps leave holes in the track`() {
        val all = flight.recordedTrack(t0, gaps = false)
        val gappy = flight.recordedTrack(t0, gaps = true)
        assertTrue(gappy.size < all.size)
        val inGaps = all.count { GpsGaps.inGap((it.timeMillis - t0) / 1000.0, flight.durationSeconds) }
        assertEquals(all.size - inGaps, gappy.size)
        assertTrue(gappy.none { GpsGaps.inGap((it.timeMillis - t0) / 1000.0, flight.durationSeconds) })
    }

    @Test
    fun `speeds and altitudes come from the simulated profile`() {
        val points = flight.recordedTrack(t0, gaps = false)
        assertTrue(points.all { it.altitudeMeters != null && it.speedMetersPerSecond != null })
        assertEquals(0.0, points.last().speedMetersPerSecond!!, 0.0)
    }
}
