package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
private const val KML_NS = "http://www.opengis.net/kml/2.2"
private const val GX_NS = "http://www.google.com/kml/ext/2.2"

/** Parses [xml] with namespaces, as a reader of the exported file would; throws if it isn't well-formed. */
private fun parse(xml: String): Document =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        .parse(xml.byteInputStream(Charsets.UTF_8))

private fun Document.all(ns: String, name: String): List<Element> =
    getElementsByTagNameNS(ns, name).let { list -> (0 until list.length).map { list.item(it) as Element } }

private fun Element.child(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length == 0) null else it.item(0) as Element }

private fun Element.childNames(): List<String> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().map { it.localName }

class TrackExportTest {

    private val t0 = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.6398, -73.7789)
    private val points = listOf(
        TrackPoint(t0, LatLon(51.47, -0.4543), 120.0, 80.0),
        // Milliseconds are dropped from the time; the altitude may be unknown.
        TrackPoint(t0 + 5_250L, LatLon(51.48123, -0.5), null, 90.0),
        TrackPoint(t0 + 3_600_000L, LatLon(52.0, -10.25), 11_000.4, 250.0),
    )
    private val flight = ExportFlight(
        codes = FlightCodes("LHR", "JFK"),
        origin = lhr,
        destination = jfk,
        simulated = false,
        points = points,
        originName = "London Heathrow Airport",
        destinationName = "John F Kennedy International Airport",
    )

    private fun gpx(f: ExportFlight = flight) = buildString { GpxWriter.write(f, this) }
    private fun kml(f: ExportFlight = flight) = buildString { KmlWriter.write(f, this) }

    // GPX 1.1

    @Test
    fun `gpx is well-formed GPX 1_1 in its namespace`() {
        val text = gpx()
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        val root = parse(text).documentElement
        assertEquals(GPX_NS, root.namespaceURI)
        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue(root.getAttribute("creator").startsWith("Flyoff"))
    }

    @Test
    fun `gpx track points have lat, lon, elevation and UTC time`() {
        val doc = parse(gpx())
        val trkpts = doc.all(GPX_NS, "trkpt")
        assertEquals(3, trkpts.size)
        val first = trkpts[0]
        assertEquals("51.47000", first.getAttribute("lat"))
        assertEquals("-0.45430", first.getAttribute("lon"))
        assertEquals("120.0", first.child(GPX_NS, "ele")!!.textContent)
        assertEquals("2026-01-01T00:00:00Z", first.child(GPX_NS, "time")!!.textContent)
        // The schema's order: ele before time.
        assertEquals(listOf("ele", "time"), first.childNames())
        assertEquals("2026-01-01T00:00:05Z", trkpts[1].child(GPX_NS, "time")!!.textContent)
        assertNull(trkpts[1].child(GPX_NS, "ele"))
        assertEquals("2026-01-01T01:00:00Z", trkpts[2].child(GPX_NS, "time")!!.textContent)
        assertEquals("11000.4", trkpts[2].child(GPX_NS, "ele")!!.textContent)
        assertEquals(1, doc.all(GPX_NS, "trkseg").size)
    }

    @Test
    fun `gpx has the airports as waypoints`() {
        val wpts = parse(gpx()).all(GPX_NS, "wpt")
        assertEquals(2, wpts.size)
        assertEquals("51.47060", wpts[0].getAttribute("lat"))
        assertEquals("-0.46194", wpts[0].getAttribute("lon"))
        assertEquals("LHR", wpts[0].child(GPX_NS, "name")!!.textContent)
        assertEquals("London Heathrow Airport", wpts[0].child(GPX_NS, "desc")!!.textContent)
        assertEquals("JFK", wpts[1].child(GPX_NS, "name")!!.textContent)
        assertEquals("40.63980", wpts[1].getAttribute("lat"))
        assertEquals("-73.77890", wpts[1].getAttribute("lon"))
    }

    @Test
    fun `gpx elements come in the schema's order`() {
        val root = parse(gpx()).documentElement
        assertEquals(listOf("metadata", "wpt", "wpt", "trk"), root.childNames())
        val trk = root.child(GPX_NS, "trk")!!
        assertEquals(listOf("name", "desc", "type", "trkseg"), trk.childNames())
        val wpt = root.child(GPX_NS, "wpt")!!
        assertEquals(listOf("name", "desc", "type"), wpt.childNames())
        val metadata = root.child(GPX_NS, "metadata")!!
        assertEquals(listOf("name", "desc", "time"), metadata.childNames())
    }

    @Test
    fun `gpx escapes names`() {
        val odd = flight.copy(originName = "Bob's <\"Fly\"> & Co", destinationName = "]]> é ✈")
        val doc = parse(gpx(odd))
        val wpts = doc.all(GPX_NS, "wpt")
        assertEquals("Bob's <\"Fly\"> & Co", wpts[0].child(GPX_NS, "desc")!!.textContent)
        assertEquals("]]> é ✈", wpts[1].child(GPX_NS, "desc")!!.textContent)
    }

    @Test
    fun `gpx of a simulated flight says so`() {
        val doc = parse(gpx(flight.copy(simulated = true)))
        val trk = doc.all(GPX_NS, "trk").single()
        assertEquals("Simulated flight", trk.child(GPX_NS, "type")!!.textContent)
        assertTrue(trk.child(GPX_NS, "desc")!!.textContent.contains("simulat", ignoreCase = true))
    }

    @Test
    fun `gpx without airport positions has no waypoints`() {
        val doc = parse(gpx(flight.copy(origin = null, destination = null)))
        assertEquals(0, doc.all(GPX_NS, "wpt").size)
        assertEquals(3, doc.all(GPX_NS, "trkpt").size)
    }

    @Test
    fun `gpx of an empty or one-point track`() {
        val empty = parse(gpx(flight.copy(points = emptyList())))
        assertEquals(0, empty.all(GPX_NS, "trkpt").size)
        assertEquals(1, empty.all(GPX_NS, "trk").size)
        assertEquals(2, empty.all(GPX_NS, "wpt").size)
        val one = parse(gpx(flight.copy(points = points.take(1))))
        assertEquals(1, one.all(GPX_NS, "trkpt").size)
    }

    @Test
    fun `gpx numbers don't depend on the phone's language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val trkpt = parse(gpx()).all(GPX_NS, "trkpt")[2]
            assertEquals("52.00000", trkpt.getAttribute("lat"))
            assertEquals("11000.4", trkpt.child(GPX_NS, "ele")!!.textContent)
        } finally {
            Locale.setDefault(previous)
        }
    }

    // KML 2.2

    @Test
    fun `kml is well-formed KML 2_2 with the gx extension namespace`() {
        val text = kml()
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        val root = parse(text).documentElement
        assertEquals(KML_NS, root.namespaceURI)
        assertEquals("kml", root.localName)
        assertEquals(1, parse(text).all(GX_NS, "Track").size)
    }

    @Test
    fun `kml airports are placemarks with lon,lat coordinates`() {
        val doc = parse(kml())
        val points = doc.all(KML_NS, "Point")
        assertEquals(2, points.size)
        assertEquals("-0.46194,51.47060", points[0].child(KML_NS, "coordinates")!!.textContent.trim())
        assertEquals("-73.77890,40.63980", points[1].child(KML_NS, "coordinates")!!.textContent.trim())
        val names = doc.all(KML_NS, "Placemark").map { it.child(KML_NS, "name")!!.textContent }
        assertEquals(listOf("LHR", "JFK", "LHR → JFK"), names)
    }

    @Test
    fun `kml track has a UTC time and a lon lat alt coordinate per point`() {
        val doc = parse(kml())
        val track = doc.all(GX_NS, "Track").single()
        val whens = track.getElementsByTagNameNS(KML_NS, "when")
        val coords = track.getElementsByTagNameNS(GX_NS, "coord")
        assertEquals(3, whens.length)
        assertEquals(3, coords.length)
        assertEquals("2026-01-01T00:00:00Z", whens.item(0).textContent)
        assertEquals("2026-01-01T00:00:05Z", whens.item(1).textContent)
        // Longitude first, then latitude, then altitude, separated by spaces.
        assertEquals("-0.45430 51.47000 120.0", coords.item(0).textContent)
        assertEquals("-10.25000 52.00000 11000.4", coords.item(2).textContent)
        // The altitude is GPS height, so the track is absolute, not clamped to the ground.
        assertEquals("absolute", track.child(KML_NS, "altitudeMode")!!.textContent)
        // All the times come before the coordinates, as KML's schema has it.
        val names = track.childNames()
        assertEquals(listOf("altitudeMode", "when", "when", "when", "coord", "coord", "coord"), names)
    }

    @Test
    fun `kml point with unknown altitude has only lon and lat`() {
        val coords = parse(kml()).all(GX_NS, "coord")
        assertEquals("-0.50000 51.48123", coords[1].textContent)
    }

    @Test
    fun `kml escapes names`() {
        val odd = flight.copy(originName = "Bob's <\"Fly\"> & Co")
        val doc = parse(kml(odd))
        val origin = doc.all(KML_NS, "Placemark")[0]
        assertTrue(origin.child(KML_NS, "description")!!.textContent.contains("Bob's <\"Fly\"> & Co"))
    }

    @Test
    fun `kml of an empty or one-point track`() {
        val empty = parse(kml(flight.copy(points = emptyList())))
        assertEquals(0, empty.all(GX_NS, "Track").size)
        assertEquals(2, empty.all(KML_NS, "Point").size)
        val one = parse(kml(flight.copy(points = points.take(1))))
        assertEquals(1, one.all(GX_NS, "coord").size)
    }

    @Test
    fun `kml of a simulated flight says so`() {
        val doc = parse(kml(flight.copy(simulated = true)))
        val track = doc.all(KML_NS, "Placemark").last()
        assertTrue(track.child(KML_NS, "description")!!.textContent.contains("simulat", ignoreCase = true))
    }

    // Both

    @Test
    fun `an exported file holds only recorded GPS fixes`() {
        // Dead-reckoned positions are never recorded, so the export has exactly the record's points.
        assertEquals(points.size, parse(gpx()).all(GPX_NS, "trkpt").size)
        assertEquals(points.size, parse(kml()).all(GX_NS, "coord").size)
    }

    @Test
    fun `file names carry the date and route`() {
        val record = FlightRecord(FlightCodes("LHR", "JFK"), lhr, jfk, simulated = false, points = points)
        assertEquals("flyoff-2026-01-01-LHR-JFK.gpx", TrackExport.fileName(record, "gpx", ZoneId.of("UTC")))
        // 2026-01-01T00:00Z is still 31 December in New York.
        assertEquals("flyoff-2025-12-31-LHR-JFK.kml", TrackExport.fileName(record, "kml", ZoneId.of("America/New_York")))
        val simulated = record.copy(simulated = true)
        assertEquals("flyoff-2026-01-01-LHR-JFK-simulated.gpx", TrackExport.fileName(simulated, "gpx", ZoneId.of("UTC")))
    }

    @Test
    fun `export of a record uses its fields and the airport names`() {
        val record = FlightRecord(FlightCodes("LHR", "JFK"), lhr, jfk, simulated = true, points = points)
        val export = record.toExport(mapOf("LHR" to "London Heathrow Airport")::get)
        assertEquals("London Heathrow Airport", export.originName)
        assertNull(export.destinationName)
        assertTrue(export.simulated)
        assertEquals(points, export.points)
        assertFalse(parse(buildString { GpxWriter.write(export, this) }).all(GPX_NS, "wpt").isEmpty())
    }
}
