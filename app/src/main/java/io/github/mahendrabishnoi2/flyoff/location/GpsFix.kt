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
 * The dot turns into an arrow along the GPS course at [ARROW_ON_SPEED] and back
 * into a dot below [ARROW_OFF_SPEED]. The gap stops it flickering at walking
 * pace; below about 0.5 m/s the GPS course is mostly noise.
 */
const val ARROW_ON_SPEED = 1.5f
const val ARROW_OFF_SPEED = 0.5f

/** True when the GPS course is worth showing as an arrow, given whether it is shown now. */
fun GpsFix.showsBearing(shownNow: Boolean): Boolean {
    val speed = speedMetersPerSecond
    if (!hasBearing || speed == null) return false
    return speed >= if (shownNow) ARROW_OFF_SPEED else ARROW_ON_SPEED
}

/**
 * The GPS course over the ground for the flight stats, or null when there is none worth
 * showing: no bearing, or too slow for the bearing to mean anything (below [ARROW_OFF_SPEED],
 * the same rule as the map's arrow). The stats then fall back to the course to the destination.
 */
fun gpsCourse(hasBearing: Boolean, bearing: Float, hasSpeed: Boolean, speed: Float): Double? =
    if (hasBearing && hasSpeed && speed >= ARROW_OFF_SPEED) bearing.toDouble() else null

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
