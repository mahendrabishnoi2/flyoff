package io.github.mahendrabishnoi2.flyoff.ui

import io.github.mahendrabishnoi2.flyoff.flight.formatEta
import java.text.NumberFormat
import java.time.DateTimeException
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Text for the flight stats. Metric units for now. Unit symbols (km, m, km/h, h, min) are SI
 * symbols, the same in every language, so they stay in code; digits and separators follow the
 * locale.
 */

/** Shown in place of a value the GPS (or the maths) can't give. Never 0, which would be a claim. */
const val UNKNOWN = "—"

/** "5,540 km", or "9.5 km" with one decimal below 10 km. Negative counts as 0. */
fun formatDistance(meters: Double?, locale: Locale): String {
    if (meters == null || meters.isNaN()) return UNKNOWN
    val km = meters.coerceAtLeast(0.0) / 1000.0
    val tenths = (km * 10).roundToLong() / 10.0
    val format = NumberFormat.getNumberInstance(locale)
    if (tenths < 10.0) {
        format.minimumFractionDigits = 1
        format.maximumFractionDigits = 1
        return "${format.format(tenths)} km"
    }
    format.maximumFractionDigits = 0
    return "${format.format(km.roundToLong())} km"
}

/** "42 min", "6 h 05 min", "< 1 min", rounded to the nearest minute. Negative is unknown. */
fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return UNKNOWN
    if (seconds in 1 until 30) return "< 1 min"
    val minutes = (seconds + 30) / 60
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else "$hours h ${(minutes % 60).toString().padStart(2, '0')} min"
}

/** Ground speed in km/h: "900 km/h". */
fun formatSpeed(metersPerSecond: Double?, locale: Locale): String {
    if (metersPerSecond == null || metersPerSecond.isNaN()) return UNKNOWN
    return "${NumberFormat.getIntegerInstance(locale).format((metersPerSecond * 3.6).roundToLong())} km/h"
}

/** "10,668 m". */
fun formatAltitude(meters: Double?, locale: Locale): String {
    if (meters == null || meters.isNaN()) return UNKNOWN
    return "${NumberFormat.getIntegerInstance(locale).format(meters.roundToLong())} m"
}

private val COMPASS_POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** The nearest of the eight compass points for a bearing in degrees (any value, taken modulo 360). */
fun compassPoint(degrees: Double): String {
    val index = Math.floorMod((degrees / 45.0).roundToLong(), 8L).toInt()
    return COMPASS_POINTS[index]
}

/** "274° W": whole degrees in 0..359 and the compass point. */
fun formatHeading(degrees: Double?, locale: Locale): String {
    if (degrees == null || degrees.isNaN()) return UNKNOWN
    val whole = Math.floorMod(degrees.roundToInt(), 360)
    return "${NumberFormat.getIntegerInstance(locale).format(whole)}° ${compassPoint(whole.toDouble())}"
}

/**
 * "42%" for a fraction of 0.42. It rounds down, so "100%" only shows on arrival, never with
 * kilometres still to go.
 */
fun formatProgress(fraction: Double?, locale: Locale): String {
    if (fraction == null || fraction.isNaN()) return UNKNOWN
    // The tiny nudge keeps 0.29 (28.999… in binary) at 29 %.
    val percent = floor(fraction.coerceIn(0.0, 1.0) * 100 + 1e-9)
    return NumberFormat.getPercentInstance(locale).format(percent / 100)
}

/** A stat cell needs about this much width at the normal font size. */
private const val STAT_CELL_DP = 100f

/**
 * How many stat columns fit in [widthDp]: cells grow with the user's [fontScale], so large text
 * gets fewer, wider columns. Never fewer than two (the ETA spans two cells).
 */
fun statColumns(widthDp: Float, fontScale: Float): Int =
    floor(widthDp / (STAT_CELL_DP * fontScale.coerceAtLeast(1f))).toInt().coerceIn(2, 6)

/** An arrival time ready to show; [inUtc] when it had to fall back to UTC. */
data class EtaText(val text: String, val inUtc: Boolean)

/**
 * The ETA in the destination's time zone ([formatEta]). Some zone ids in the airport list are
 * newer than an old phone's time-zone data (America/Ciudad_Juarez, America/Coyhaique); for those
 * the time is shown in UTC and [EtaText.inUtc] says so, so the UI can label it.
 */
fun formatEtaOrUtc(etaMillis: Long?, nowMillis: Long, zoneId: String, locale: Locale): EtaText {
    if (etaMillis == null) return EtaText(UNKNOWN, inUtc = false)
    return try {
        EtaText(formatEta(etaMillis, nowMillis, zoneId, locale), inUtc = false)
    } catch (_: DateTimeException) {
        // ZoneRulesException (an id this device doesn't know) is a DateTimeException too.
        EtaText(formatEta(etaMillis, nowMillis, "UTC", locale), inUtc = true)
    }
}

/**
 * "34 MB": an estimate's upper bound, rounded up to whole megabytes (1,000,000 bytes, as
 * OpenFreeMap's 50 MB limit counts them), at least 1.
 */
fun formatUpToMegabytes(bytes: Long, locale: Locale): String {
    val mb = ((bytes.coerceAtLeast(0) + 999_999) / 1_000_000).coerceAtLeast(1)
    return "${NumberFormat.getIntegerInstance(locale).format(mb)} MB"
}

/** "12.3 MB": a stored size, with one decimal. */
fun formatMegabytes(bytes: Long, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = 1
    format.maximumFractionDigits = 1
    return "${format.format(bytes.coerceAtLeast(0) / 1_000_000.0)} MB"
}
