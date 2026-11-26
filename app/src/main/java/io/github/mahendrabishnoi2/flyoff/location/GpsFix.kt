package io.github.mahendrabishnoi2.flyoff.location

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The parts of a GPS fix the status line shows. Each value is null when the
 * receiver didn't report it.
 */
data class GpsFix(
    val accuracyMeters: Float?,
    /** Height above the WGS84 ellipsoid, as GPS reports it; it can differ from sea level by up to ~100 m. */
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val hasBearing: Boolean,
)

/**
 * Speed above which the dot turns into an arrow pointing along the GPS course.
 * Below it, GPS course is mostly noise. 1 m/s (3.6 km/h) is a slow walk.
 */
const val MOVING_SPEED_METERS_PER_SECOND = 1.0f

/** True when the GPS course is worth showing as an arrow. */
fun GpsFix.showsBearing(): Boolean =
    hasBearing && (speedMetersPerSecond ?: 0f) >= MOVING_SPEED_METERS_PER_SECOND

fun metersPerSecondToKmh(speed: Float): Float = speed * 3.6f

/**
 * One line such as "±5 m · GPS alt 10,668 m · 842 km/h". Values the receiver
 * didn't report are left out. Numbers use [locale]'s digit grouping.
 */
fun GpsFix.summary(locale: Locale): String {
    val number = NumberFormat.getIntegerInstance(locale)
    return listOfNotNull(
        accuracyMeters?.let { "±${number.format(it.roundToInt())} m" },
        altitudeMeters?.let { "GPS alt ${number.format(it.roundToInt())} m" },
        speedMetersPerSecond?.let { "${number.format(metersPerSecondToKmh(it).roundToInt())} km/h" },
    ).joinToString(" · ")
}
