package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an exported file holds: a flight's route and recorded points, and the airports' names
 * for the waypoints (null shows the code alone). The points are the recorded GPS fixes only:
 * dead-reckoned positions are never recorded, so they are never exported either. An exported
 * track is measured data that other apps may treat as such, so it must not hold guesses; a GPS
 * drop-out shows as a straight line between the fixes either side, as on Flyoff's own map.
 */
data class ExportFlight(
    val codes: FlightCodes,
    val origin: LatLon?,
    val destination: LatLon?,
    val simulated: Boolean,
    val points: List<TrackPoint>,
    val originName: String?,
    val destinationName: String?,
)

/** The export of this record, with airport names from [airportName] (by IATA code). */
fun FlightRecord.toExport(airportName: (String) -> String?): ExportFlight = ExportFlight(
    codes = codes,
    origin = origin,
    destination = destination,
    simulated = simulated,
    points = points,
    originName = airportName(codes.origin),
    destinationName = airportName(codes.destination),
)

object TrackExport {

    /** "flyoff-2026-01-01-LHR-JFK.gpx": the date the flight started in [zone], "-simulated" for a simulated one. */
    fun fileName(record: FlightRecord, extension: String, zone: ZoneId): String {
        val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(record.startMillis).atZone(zone))
        val simulated = if (record.simulated) "-simulated" else ""
        return "flyoff-$date-${record.codes.origin}-${record.codes.destination}$simulated.$extension"
    }

    internal const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

    /** Whole seconds in UTC, "2026-01-01T00:00:00Z": ISO 8601, and the form GPX and KML readers all accept. */
    private val UTC_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    internal fun time(millis: Long): String = UTC_SECONDS.format(Instant.ofEpochMilli(millis))

    /** Degrees with 5 decimals, as recorded. */
    internal fun degrees(value: Double): String = String.format(Locale.ROOT, "%.5f", value)

    /** Metres with 1 decimal, as recorded. */
    internal fun meters(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /** [text] safe inside an XML element or a double-quoted attribute. */
    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    internal fun title(flight: ExportFlight): String = "${flight.codes.origin} → ${flight.codes.destination}"

    internal fun description(flight: ExportFlight): String =
        if (flight.simulated) {
            "Simulated by Flyoff's flight simulator: not a real flight."
        } else {
            "Recorded by Flyoff from the phone's GPS. Altitudes are GPS heights above the WGS 84 ellipsoid."
        }
}

/**
 * Writes a flight as GPX 1.1 (https://www.topografix.com/GPX/1/1/): metadata, the origin and
 * destination airports as waypoints (when their positions are known), and one track with one
 * segment of track points, each with its elevation (when the GPS gave one) and UTC time.
 */
object GpxWriter {

    fun write(flight: ExportFlight, out: Appendable) {
        val title = TrackExport.escape(TrackExport.title(flight))
        val description = TrackExport.escape(TrackExport.description(flight))
        out.append(TrackExport.XML_DECLARATION)
        out.append(
            "<gpx version=\"1.1\" creator=\"Flyoff\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
        out.append("  <metadata>\n")
        out.append("    <name>").append(title).append("</name>\n")
        out.append("    <desc>").append(description).append("</desc>\n")
        flight.points.firstOrNull()?.let { out.append("    <time>").append(TrackExport.time(it.timeMillis)).append("</time>\n") }
        out.append("  </metadata>\n")
        waypoint(out, flight.origin, flight.codes.origin, flight.originName, "Origin")
        waypoint(out, flight.destination, flight.codes.destination, flight.destinationName, "Destination")
        out.append("  <trk>\n")
        out.append("    <name>").append(title).append("</name>\n")
        out.append("    <desc>").append(description).append("</desc>\n")
        out.append("    <type>").append(if (flight.simulated) "Simulated flight" else "Flight").append("</type>\n")
        out.append("    <trkseg>\n")
        for (point in flight.points) {
            out.append("      <trkpt lat=\"").append(TrackExport.degrees(point.latLon.latitude))
                .append("\" lon=\"").append(TrackExport.degrees(point.latLon.longitude)).append("\">")
            point.altitudeMeters?.let { out.append("<ele>").append(TrackExport.meters(it)).append("</ele>") }
            out.append("<time>").append(TrackExport.time(point.timeMillis)).append("</time></trkpt>\n")
        }
        out.append("    </trkseg>\n")
        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    private fun waypoint(out: Appendable, at: LatLon?, code: String, name: String?, type: String) {
        if (at == null) return
        out.append("  <wpt lat=\"").append(TrackExport.degrees(at.latitude))
            .append("\" lon=\"").append(TrackExport.degrees(at.longitude)).append("\">")
        out.append("<name>").append(TrackExport.escape(code)).append("</name>")
        out.append("<desc>").append(TrackExport.escape(name ?: code)).append("</desc>")
        out.append("<type>").append(type).append("</type></wpt>\n")
    }
}

/**
 * Writes a flight as KML 2.2 (OGC 07-147r2): the origin and destination airports as placemarks
 * (`lon,lat` coordinates, when their positions are known), and the track as a placemark holding a
 * `gx:Track`, Google's KML 2.2 extension (namespace `http://www.google.com/kml/ext/2.2`, adopted
 * into OGC KML 2.3), since core KML 2.2 has no geometry with a time per point. A `gx:Track` lists
 * every `<when>` (UTC time), then every `<gx:coord>` ("lon lat alt", space-separated) in the same
 * order, with absolute (GPS) altitudes. A point without altitude has "lon lat" only. An empty
 * track has no track placemark.
 */
object KmlWriter {

    /** The track line's colour in KML's aabbggrr: Flyoff's track blue #1565c0, opaque. */
    private const val TRACK_COLOR = "ffc06515"

    fun write(flight: ExportFlight, out: Appendable) {
        val title = TrackExport.escape(TrackExport.title(flight))
        out.append(TrackExport.XML_DECLARATION)
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n")
        out.append("<Document>\n")
        out.append("  <name>").append(title).append("</name>\n")
        out.append("  <description>").append(TrackExport.escape(TrackExport.description(flight))).append("</description>\n")
        out.append("  <Style id=\"track\"><LineStyle><color>").append(TRACK_COLOR).append("</color><width>3</width></LineStyle></Style>\n")
        airport(out, flight.origin, flight.codes.origin, flight.originName, "origin")
        airport(out, flight.destination, flight.codes.destination, flight.destinationName, "destination")
        if (flight.points.isNotEmpty()) {
            out.append("  <Placemark>\n")
            out.append("    <name>").append(title).append("</name>\n")
            out.append("    <description>").append(TrackExport.escape(TrackExport.description(flight))).append("</description>\n")
            out.append("    <styleUrl>#track</styleUrl>\n")
            out.append("    <gx:Track>\n")
            out.append("      <altitudeMode>absolute</altitudeMode>\n")
            for (point in flight.points) {
                out.append("      <when>").append(TrackExport.time(point.timeMillis)).append("</when>\n")
            }
            for (point in flight.points) {
                out.append("      <gx:coord>").append(TrackExport.degrees(point.latLon.longitude))
                    .append(' ').append(TrackExport.degrees(point.latLon.latitude))
                point.altitudeMeters?.let { out.append(' ').append(TrackExport.meters(it)) }
                out.append("</gx:coord>\n")
            }
            out.append("    </gx:Track>\n")
            out.append("  </Placemark>\n")
        }
        out.append("</Document>\n")
        out.append("</kml>\n")
    }

    private fun airport(out: Appendable, at: LatLon?, code: String, name: String?, role: String) {
        if (at == null) return
        out.append("  <Placemark><name>").append(TrackExport.escape(code)).append("</name>")
        out.append("<description>").append(TrackExport.escape("${name ?: code} ($role)")).append("</description>")
        out.append("<Point><coordinates>").append(TrackExport.degrees(at.longitude)).append(',')
            .append(TrackExport.degrees(at.latitude)).append("</coordinates></Point></Placemark>\n")
    }
}
