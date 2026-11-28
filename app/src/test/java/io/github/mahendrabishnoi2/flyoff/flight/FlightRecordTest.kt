package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightRecordTest {

    private val t0 = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.6398, -73.7789)

    private fun point(i: Int) = TrackPoint(t0 + i * 5_000L, LatLon(51.47, (-45 - i) / 100.0), 10_000.0 + i, 231.5)

    private val record = FlightRecord(
        codes = FlightCodes("LHR", "JFK"),
        origin = lhr,
        destination = jfk,
        simulated = false,
        points = listOf(point(0), point(1), point(2)),
    )

    @Test
    fun `id is the start time and the route`() {
        assertEquals("1767225600000-LHR-JFK", record.id)
        assertEquals(t0, record.startMillis)
        assertEquals(t0 + 10_000L, record.endMillis)
        assertTrue(FlightRecord.isValidId(record.id))
        assertFalse(FlightRecord.isValidId("../track.log"))
        assertFalse(FlightRecord.isValidId("1767225600000-LHR-JFK/x"))
        assertFalse(FlightRecord.isValidId(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a record needs at least one point`() {
        FlightRecord(FlightCodes("LHR", "JFK"), lhr, jfk, simulated = false, points = emptyList())
    }

    @Test
    fun `encodes a header and one track line per point`() {
        val text = FlightRecordFormat.encode(record)
        val lines = text.split('\n')
        assertEquals("# Flyoff flight 1", lines[0])
        assertTrue(lines.contains("route=LHR JFK"))
        assertTrue(lines.contains("origin=51.47060,-0.46194"))
        assertTrue(lines.contains("destination=40.63980,-73.77890"))
        assertTrue(lines.contains("simulated=false"))
        assertTrue(lines.contains("start=1767225600000"))
        assertTrue(lines.contains("end=1767225610000"))
        assertTrue(lines.contains("points=3"))
        assertTrue(lines.any { it.startsWith("distance=") })
        // A blank line ends the header; then the points in TrackLog's format, each ending in a newline.
        val blank = lines.indexOf("")
        assertEquals(listOf(TrackLog.format(point(0)), TrackLog.format(point(1)), TrackLog.format(point(2)), ""), lines.subList(blank + 1, lines.size))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `round trip`() {
        val decoded = FlightRecordFormat.decode(FlightRecordFormat.encode(record))!!
        assertEquals(record.codes, decoded.codes)
        assertEquals(record.simulated, decoded.simulated)
        assertEquals(record.points, decoded.points)
        assertEquals(lhr.latitude, decoded.origin!!.latitude, 1e-5)
        assertEquals(lhr.longitude, decoded.origin!!.longitude, 1e-5)
        assertEquals(jfk.latitude, decoded.destination!!.latitude, 1e-5)
        assertEquals(record.id, decoded.id)
    }

    @Test
    fun `round trip of a simulated flight without airport positions`() {
        val simulated = record.copy(origin = null, destination = null, simulated = true)
        val text = FlightRecordFormat.encode(simulated)
        assertFalse(text.contains("origin="))
        val decoded = FlightRecordFormat.decode(text)!!
        assertTrue(decoded.simulated)
        assertNull(decoded.origin)
        assertNull(decoded.destination)
        assertEquals(simulated.points, decoded.points)
    }

    @Test
    fun `the entry is read from the header alone`() {
        val text = FlightRecordFormat.encode(record)
        val header = text.substringBefore("\n\n")
        val entry = FlightRecordFormat.decodeEntry(header.lineSequence())!!
        assertEquals(record.entry(), entry)
        assertEquals("1767225600000-LHR-JFK", entry.id)
        assertEquals(FlightCodes("LHR", "JFK"), entry.codes)
        assertEquals(10_000L, entry.durationMillis)
        assertEquals(3, entry.pointCount)
        assertEquals(FlightSummaryCalculator.distanceFlownMeters(record.points), entry.distanceMeters, 0.1)
        assertFalse(entry.simulated)
    }

    @Test
    fun `unknown header keys are ignored, so a later version can add some`() {
        val text = FlightRecordFormat.encode(record).replace("simulated=false\n", "simulated=false\nflightNumber=BA117\n")
        assertEquals(record.points, FlightRecordFormat.decode(text)!!.points)
        assertEquals(record.entry(), FlightRecordFormat.decodeEntry(text.lineSequence()))
    }

    @Test
    fun `malformed point lines are skipped`() {
        val text = FlightRecordFormat.encode(record).replace(TrackLog.format(point(1)), "garbage")
        assertEquals(listOf(point(0), point(2)), FlightRecordFormat.decode(text)!!.points)
    }

    @Test
    fun `rejects what isn't a flight record`() {
        assertNull(FlightRecordFormat.decode(""))
        assertNull(FlightRecordFormat.decode("# Flyoff track LHR JFK\n1767225600000,51.47000,-0.45000,10000.0,231.5\n"))
        assertNull(FlightRecordFormat.decode("# Flyoff flight 2\nroute=LHR JFK\n\n" + TrackLog.format(point(0)) + "\n"))
        // No route, a bad route, or no points.
        assertNull(FlightRecordFormat.decode("# Flyoff flight 1\nsimulated=false\n\n" + TrackLog.format(point(0)) + "\n"))
        assertNull(FlightRecordFormat.decode("# Flyoff flight 1\nroute=LHR LHR\n\n" + TrackLog.format(point(0)) + "\n"))
        assertNull(FlightRecordFormat.decode("# Flyoff flight 1\nroute=LHR JFK\n\n"))
        assertNull(FlightRecordFormat.decodeEntry("# Flyoff flight 1\nroute=LHR JFK\nstart=x\n".lineSequence()))
        assertNull(FlightRecordFormat.decodeEntry(emptySequence()))
    }

    @Test
    fun `a bad airport position is dropped, not the flight`() {
        val text = FlightRecordFormat.encode(record).replace("origin=51.47060,-0.46194", "origin=95.0,0.0")
        val decoded = FlightRecordFormat.decode(text)!!
        assertNull(decoded.origin)
        assertEquals(record.points, decoded.points)
    }

    @Test
    fun `numbers don't depend on the phone's language`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val text = FlightRecordFormat.encode(record)
            assertTrue(text.contains("origin=51.47060,-0.46194"))
            assertEquals(record.points, FlightRecordFormat.decode(text)!!.points)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
