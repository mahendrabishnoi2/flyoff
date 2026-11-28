package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.GZIPInputStream

class FlightLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_767_225_600_000L
    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.6398, -73.7789)
    private val cdg = LatLon(49.0097, 2.5479)

    private fun store() = FlightLogStore(File(folder.root, "flights"))

    private fun record(start: Long, codes: FlightCodes = FlightCodes("LHR", "JFK"), simulated: Boolean = false) =
        FlightRecord(
            codes, lhr, jfk, simulated,
            points = (0 until 10).map { TrackPoint(start + it * 5_000L, LatLon(51.47, (-45 - it) / 100.0), 10_000.0, 231.5) },
        )

    @Test
    fun `an empty log lists nothing`() {
        assertEquals(emptyList<FlightEntry>(), store().entries())
        // The folder doesn't even exist yet.
        assertFalse(File(folder.root, "flights").exists())
    }

    @Test
    fun `saves, lists and reads back a flight`() {
        val store = store()
        val flight = record(t0)
        store.save(flight)
        assertEquals(listOf(flight.entry()), store.entries())
        val back = store.read(flight.id)!!
        assertEquals(flight.points, back.points)
        assertEquals(flight.codes, back.codes)
    }

    @Test
    fun `lists newest first`() {
        val store = store()
        val older = record(t0)
        val newest = record(t0 + 2 * day, FlightCodes("JFK", "LHR"))
        val middle = record(t0 + day, FlightCodes("LHR", "CDG"), simulated = true)
        store.save(older)
        store.save(newest)
        store.save(middle)
        assertEquals(listOf(newest.id, middle.id, older.id), store.entries().map { it.id })
    }

    @Test
    fun `saving the same flight again replaces it`() {
        val store = store()
        val partial = record(t0).let { it.copy(points = it.points.take(3)) }
        store.save(partial)
        val full = record(t0)
        assertEquals(partial.id, full.id)
        store.save(full)
        assertEquals(listOf(full.entry()), store.entries())
        assertEquals(10, store.read(full.id)!!.points.size)
    }

    @Test
    fun `deletes one flight and keeps the others`() {
        val store = store()
        val a = record(t0)
        val b = record(t0 + day)
        store.save(a)
        store.save(b)
        assertTrue(store.delete(a.id))
        assertEquals(listOf(b.id), store.entries().map { it.id })
        assertNull(store.read(a.id))
        assertFalse(store.delete(a.id))
        assertTrue(store.delete(b.id))
        assertEquals(emptyList<FlightEntry>(), store.entries())
    }

    @Test
    fun `ids that aren't flight ids are refused`() {
        val store = store()
        store.save(record(t0))
        File(folder.root, "secret").writeText("x")
        assertNull(store.read("../secret"))
        assertFalse(store.delete("../secret"))
        assertTrue(File(folder.root, "secret").exists())
    }

    @Test
    fun `unreadable files are skipped, not fatal`() {
        val store = store()
        store.save(record(t0))
        File(folder.root, "flights/1767225600001-LHR-CDG.flight.gz").writeText("not gzip")
        File(folder.root, "flights/notes.txt").writeText("hello")
        assertEquals(1, store.entries().size)
        assertNull(store.read("1767225600001-LHR-CDG"))
    }

    @Test
    fun `files are gzipped text with a temporary file only while writing`() {
        val store = store()
        val flight = record(t0)
        store.save(flight)
        val files = File(folder.root, "flights").listFiles()!!.map { it.name }
        assertEquals(listOf("${flight.id}.flight.gz"), files)
        val text = GZIPInputStream(File(folder.root, "flights/${flight.id}.flight.gz").inputStream()).reader().readText()
        assertEquals(FlightRecordFormat.encode(flight), text)
    }

    /**
     * Storage per flight-hour: one point per 5 s is 720 points an hour. The simulator's smooth
     * track compresses better than real GPS, so this one gets GPS-like noise in every field.
     */
    @Test
    fun `an hour of flight takes about 12 kB`() {
        val random = Random(42)
        val flight = SimulatedFlight(lhr, jfk)
        val points = (0 until 720).map { i ->
            val p = flight.positionAt(i * 5.0 + 1_800.0, t0)
            TrackPoint(
                timeMillis = p.timeMillis + random.nextInt(200),
                latLon = LatLon(p.latLon.latitude + random.nextGaussian() * 3e-5, p.latLon.longitude + random.nextGaussian() * 3e-5),
                altitudeMeters = p.altitudeMeters!! + random.nextGaussian() * 5,
                speedMetersPerSecond = p.speedMetersPerSecond!! + random.nextGaussian() * 0.5,
            )
        }
        val record = FlightRecord(FlightCodes("LHR", "JFK"), lhr, jfk, simulated = false, points = points)
        val bytes = store().save(record)
        val plain = FlightRecordFormat.encode(record).toByteArray().size
        println("one flight-hour: $bytes bytes gzipped, $plain bytes as text, ${points.size} points")
        assertTrue("$bytes bytes for an hour", bytes in 5_000..16_000)
        // And the simulator's own track, as CI records it.
        val simulated = FlightRecord(FlightCodes("LHR", "CDG"), lhr, cdg, simulated = true, points = SimulatedFlight(lhr, cdg).recordedTrack(t0, gaps = false))
        val simBytes = store().save(simulated)
        val hours = (simulated.endMillis - simulated.startMillis) / 3_600_000.0
        println("simulated LHR-CDG: $simBytes bytes for ${"%.2f".format(hours)} h, ${(simBytes / hours).toLong()} bytes per hour")
    }
}
