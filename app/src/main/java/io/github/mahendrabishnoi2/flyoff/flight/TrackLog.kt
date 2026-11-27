package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import java.util.Locale

/** One recorded point of the flown track. */
data class TrackPoint(
    /** Epoch milliseconds. */
    val timeMillis: Long,
    val latLon: LatLon,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
)

/**
 * The flown track as a text file, one point per line:
 * `time,latitude,longitude,altitude,speed`, for example
 * `1767225600000,51.47000,-0.45430,10000.0,231.5`.
 *
 * - time: epoch milliseconds, an integer.
 * - latitude and longitude: degrees with 5 decimals (at most 1.1 m on the ground). The longitude
 *   is written as given, so an unwrapped one such as 200.12346 stays unwrapped.
 * - altitude (metres) and speed (m/s): 1 decimal, or an empty field when unknown.
 *
 * Numbers use `.` and plain ASCII digits whatever the phone's language. Lines are appended while
 * the app records, so a line can be cut short if the app dies mid-write; [parse] rejects what it
 * can tell is broken. A line cut inside its last field (`…,231` for `…,231.5`) still looks valid,
 * so a reader should drop a last line that has no newline after it.
 */
object TrackLog {

    /** An optional minus, digits, and an optional fraction with at least one digit. No exponent. */
    private val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?")
    private val INTEGER = Regex("-?[0-9]+")
    private const val FIELDS = 5

    /** One line (no newline), stable and locale-independent, e.g. "1767225600000,51.47000,-0.45430,10000.0,231.5" with empty fields for nulls. */
    fun format(point: TrackPoint): String = buildString {
        append(point.timeMillis)
        append(',').append(fixed(point.latLon.latitude, 5))
        append(',').append(fixed(point.latLon.longitude, 5))
        append(',').append(point.altitudeMeters?.let { fixed(it, 1) } ?: "")
        append(',').append(point.speedMetersPerSecond?.let { fixed(it, 1) } ?: "")
    }

    /** The point on [line], or null for a blank, malformed or half-written line (the app may die mid-write). Never throws. */
    fun parse(line: String): TrackPoint? {
        val fields = line.trim().split(',')
        if (fields.size != FIELDS) return null
        val time = fields[0].takeIf { INTEGER.matches(it) }?.toLongOrNull() ?: return null
        val latitude = number(fields[1]) ?: return null
        val longitude = number(fields[2]) ?: return null
        if (latitude !in -90.0..90.0) return null
        val altitude = optionalNumber(fields[3]) ?: return null
        val speed = optionalNumber(fields[4]) ?: return null
        return TrackPoint(time, LatLon(latitude, longitude), altitude.value, speed.value)
    }

    private fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    /** A finite number in our own format, or null. */
    private fun number(field: String): Double? =
        if (DECIMAL.matches(field)) field.toDouble().takeIf { it.isFinite() } else null

    /** Present-but-empty is a known null; anything else must be a number. Null means malformed. */
    private fun optionalNumber(field: String): Optional? = when {
        field.isEmpty() -> Optional(null)
        else -> number(field)?.let { Optional(it) }
    }

    private class Optional(val value: Double?)
}

/** Keeps a track small: one point per [minIntervalMillis]. 5 s gives about 7,200 points (~0.4 MB) for a 10-hour flight. */
class TrackDecimator(private val minIntervalMillis: Long = 5_000) {

    init {
        require(minIntervalMillis >= 0) { "minIntervalMillis $minIntervalMillis must not be negative" }
    }

    private var lastKeptMillis: Long? = null

    /** True if [point] should be kept: the first point, one at least minIntervalMillis after the last kept point, or one whose time went backwards (then it restarts from it). */
    fun accept(point: TrackPoint): Boolean {
        val last = lastKeptMillis
        val keep = last == null || point.timeMillis < last || point.timeMillis - last >= minIntervalMillis
        if (keep) lastKeptMillis = point.timeMillis
        return keep
    }

    /** Forgets the last kept point, so the next one is kept (for a new flight). */
    fun reset() {
        lastKeptMillis = null
    }
}
