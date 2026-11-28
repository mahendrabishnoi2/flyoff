package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import java.util.Locale

/**
 * One flight in the flight log: its route, the airports' positions when known, whether the
 * flight simulator made it, and its recorded (decimated) track. Only GPS fixes are ever
 * recorded, never dead-reckoned estimates.
 */
data class FlightRecord(
    val codes: FlightCodes,
    val origin: LatLon?,
    val destination: LatLon?,
    val simulated: Boolean,
    /** In the order recorded; never empty. */
    val points: List<TrackPoint>,
) {
    init {
        require(points.isNotEmpty()) { "a flight record needs at least one point" }
    }

    /** The first point's time. */
    val startMillis: Long get() = points.first().timeMillis

    /** The last point's time. */
    val endMillis: Long get() = points.last().timeMillis

    /**
     * "1767225600000-LHR-JFK": the first point's time and the route. A track that is continued
     * (stopped and started again, or resumed after Android closed the app) keeps its first
     * point, so saving it again replaces the same flight.
     */
    val id: String get() = idOf(codes, startMillis)

    /** What the history list shows, without the track. The distance is rounded to 0.1 m, as stored. */
    fun entry(): FlightEntry = FlightEntry(
        id = id,
        codes = codes,
        simulated = simulated,
        startMillis = startMillis,
        endMillis = endMillis,
        distanceMeters = Math.round(FlightSummaryCalculator.distanceFlownMeters(points) * 10) / 10.0,
        pointCount = points.size,
    )

    companion object {
        private val ID = Regex("[0-9]{1,19}-[A-Z]{3}-[A-Z]{3}")

        fun idOf(codes: FlightCodes, startMillis: Long): String = "$startMillis-${codes.origin}-${codes.destination}"

        /** True for an id as [idOf] makes it: safe to use as a file name. */
        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}

/** One line of the flight history: a [FlightRecord]'s header. */
data class FlightEntry(
    val id: String,
    val codes: FlightCodes,
    val simulated: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    /** Distance flown along the recorded track ([FlightSummaryCalculator.distanceFlownMeters]). */
    val distanceMeters: Double,
    val pointCount: Int,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

/**
 * A [FlightRecord] as text. A header of `key=value` lines after a version line, a blank line,
 * then one [TrackLog] line per point:
 *
 * ```
 * # Flyoff flight 1
 * route=LHR JFK
 * origin=51.47060,-0.46194
 * destination=40.63980,-73.77890
 * simulated=false
 * start=1767225600000
 * end=1767248400000
 * distance=5612345.6
 * points=4561
 *
 * 1767225600000,51.47000,-0.45430,120.0,80.0
 * …
 * ```
 *
 * `origin` and `destination` (degrees, 5 decimals) are left out when unknown. `start`, `end`,
 * `distance` (metres) and `points` repeat what the points say, so the history list can be read
 * from the header alone ([decodeEntry]). Unknown keys are ignored, so a later version can add
 * some; a different version line is not read. Numbers use `.` whatever the phone's language.
 */
object FlightRecordFormat {

    private const val VERSION_LINE = "# Flyoff flight 1"

    fun encode(record: FlightRecord): String = buildString {
        val entry = record.entry()
        append(VERSION_LINE).append('\n')
        append("route=").append(record.codes.origin).append(' ').append(record.codes.destination).append('\n')
        record.origin?.let { append("origin=").append(latLon(it)).append('\n') }
        record.destination?.let { append("destination=").append(latLon(it)).append('\n') }
        append("simulated=").append(record.simulated).append('\n')
        append("start=").append(entry.startMillis).append('\n')
        append("end=").append(entry.endMillis).append('\n')
        append("distance=").append(String.format(Locale.ROOT, "%.1f", entry.distanceMeters)).append('\n')
        append("points=").append(entry.pointCount).append('\n')
        append('\n')
        for (point in record.points) append(TrackLog.format(point)).append('\n')
    }

    /** The record in [text], or null when it isn't one (or has no readable point). Never throws. */
    fun decode(text: String): FlightRecord? {
        val lines = text.split('\n')
        val header = header(lines.asSequence()) ?: return null
        val blank = lines.indexOf("")
        if (blank < 0) return null
        val points = lines.subList(blank + 1, lines.size).mapNotNull(TrackLog::parse)
        if (points.isEmpty()) return null
        return FlightRecord(
            codes = header.codes,
            origin = header.values["origin"]?.let(::parseLatLon),
            destination = header.values["destination"]?.let(::parseLatLon),
            simulated = header.values["simulated"] == "true",
            points = points,
        )
    }

    /** The history entry from a record's header [lines] (reading stops at the blank line), or null. */
    fun decodeEntry(lines: Sequence<String>): FlightEntry? {
        val header = header(lines) ?: return null
        val values = header.values
        val start = values["start"]?.toLongOrNull() ?: return null
        val end = values["end"]?.toLongOrNull() ?: return null
        val distance = values["distance"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val points = values["points"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return FlightEntry(
            id = FlightRecord.idOf(header.codes, start),
            codes = header.codes,
            simulated = values["simulated"] == "true",
            startMillis = start,
            endMillis = end,
            distanceMeters = distance,
            pointCount = points,
        )
    }

    private class Header(val codes: FlightCodes, val values: Map<String, String>)

    private fun header(lines: Sequence<String>): Header? {
        val iterator = lines.iterator()
        if (!iterator.hasNext() || iterator.next() != VERSION_LINE) return null
        val values = HashMap<String, String>()
        while (iterator.hasNext()) {
            val line = iterator.next()
            if (line.isEmpty()) break
            val eq = line.indexOf('=')
            if (eq > 0) values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val route = values["route"]?.split(' ') ?: return null
        if (route.size != 2) return null
        val codes = FlightCodes.parse(route[0], route[1]) ?: return null
        return Header(codes, values)
    }

    private fun latLon(p: LatLon): String = String.format(Locale.ROOT, "%.5f,%.5f", p.latitude, p.longitude)

    private fun parseLatLon(text: String): LatLon? {
        val parts = text.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return runCatching { LatLon(lat, lon) }.getOrNull()
    }
}
