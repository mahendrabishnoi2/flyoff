package io.github.mahendrabishnoi2.flyoff.tracking

import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.Position
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TrackingTextTest {

    private val en = Locale.US
    private val lhrJfk = TrackedFlight(
        codes = FlightCodes("LHR", "JFK"),
        origin = LatLon(51.4706, -0.461941),
        destination = LatLon(40.639801, -73.7789),
        destinationZone = "America/New_York",
    )

    /** 2026-01-15 12:00 UTC: winter, so New York is on EST (UTC−5). */
    private val noon = 1_768_478_400_000L

    private fun fix(latLon: LatLon, speed: Double?) = Position(latLon, 10_000.0, speed, null, noon)

    private fun text(enabled: Boolean, fix: Position?, age: Long) =
        trackingNotificationText(lhrJfk, enabled, fix, age, noon, en)

    @Test
    fun fixAgeRoundsDown() {
        assertEquals("< 1 min", formatFixAge(0))
        assertEquals("< 1 min", formatFixAge(59_999))
        assertEquals("1 min", formatFixAge(60_000))
        assertEquals("4 min", formatFixAge(4 * 60_000 + 59_000))
        assertEquals("1 h 05 min", formatFixAge(65 * 60_000L))
        assertEquals("< 1 min", formatFixAge(-5_000))
    }

    @Test
    fun estimatedLine() {
        assertEquals("Estimated position · last GPS fix 4 min ago", estimatedPositionLine(4 * 60_000L + 10_000))
    }

    @Test
    fun titleNamesTheFlight() {
        assertEquals("Tracking LHR → JFK", text(true, null, 0).title)
    }

    @Test
    fun locationOffComesFirst() {
        assertEquals("Location is off · turn it on to keep recording", text(false, fix(lhrJfk.origin, 250.0), 0).text)
    }

    @Test
    fun waitingBeforeTheFirstFix() {
        assertEquals("Waiting for GPS…", text(true, null, 0).text)
    }

    @Test
    fun liveFixGivesDistanceAndEta() {
        // At Heathrow at 250 m/s: 5,540 km at 900 km/h is 6 h 09 min, arriving 18:09 UTC = 13:09 EST.
        assertEquals("5,540 km to go · ETA 13:09 EST", text(true, fix(lhrJfk.origin, 250.0), 9_999).text)
    }

    @Test
    fun noEtaBelowFiftyKmh() {
        assertEquals("5,540 km to go", text(true, fix(lhrJfk.origin, 5.0), 0).text)
        assertEquals("5,540 km to go", text(true, fix(lhrJfk.origin, null), 0).text)
    }

    @Test
    fun staleFixWithSpeedIsAnEstimate() {
        assertEquals(
            "Estimated position · last GPS fix 4 min ago",
            text(true, fix(lhrJfk.origin, 250.0), 4 * 60_000L).text,
        )
        assertEquals("Estimated position · last GPS fix < 1 min ago", text(true, fix(lhrJfk.origin, 250.0), 10_000).text)
    }

    @Test
    fun staleFixWithoutSpeedOrTooOldIsJustTheAge() {
        assertEquals("Last GPS fix 4 min ago", text(true, fix(lhrJfk.origin, 3.0), 4 * 60_000L).text)
        assertEquals("Last GPS fix 3 h 01 min ago", text(true, fix(lhrJfk.origin, 250.0), 181 * 60_000L).text)
    }

    @Test
    fun unknownZoneFallsBackToUtc() {
        val odd = lhrJfk.copy(destinationZone = "Not/AZone")
        val line = trackingNotificationText(odd, true, fix(lhrJfk.origin, 250.0), 0, noon, en).text
        assertEquals("5,540 km to go · ETA 18:09 UTC", line)
    }
}
