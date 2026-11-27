package io.github.mahendrabishnoi2.flyoff.tracking

import io.github.mahendrabishnoi2.flyoff.flight.DeadReckoning
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightStatsCalculator
import io.github.mahendrabishnoi2.flyoff.flight.Position
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.ui.UNKNOWN
import io.github.mahendrabishnoi2.flyoff.ui.formatDistance
import io.github.mahendrabishnoi2.flyoff.ui.formatEtaOrUtc
import java.util.Locale

/*
 * Wording shared by the tracking notification and the flight panel. English in code, like
 * GpsFix.summary; numbers and times follow the locale.
 */

/**
 * How long ago the last GPS fix was, rounded down: "< 1 min", "4 min", "1 h 05 min". Rounding
 * down never makes a fix sound older than it is.
 */
fun formatFixAge(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    if (minutes == 0L) return "< 1 min"
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else "$hours h ${(minutes % 60).toString().padStart(2, '0')} min"
}

/** "Estimated position · last GPS fix 4 min ago": the panel line under an estimated dot. */
fun estimatedPositionLine(millisSinceFix: Long): String =
    "Estimated position · last GPS fix ${formatFixAge(millisSinceFix)} ago"

/** The flight being tracked: what the tracking service needs to word its notification. */
data class TrackedFlight(
    val codes: FlightCodes,
    val origin: LatLon,
    val destination: LatLon,
    /** The destination's IANA time zone, for the ETA. */
    val destinationZone: String,
)

/** The tracking notification's two lines. */
data class NotificationText(val title: String, val text: String)

/**
 * The tracking notification: "Tracking LHR → JFK" over one of
 *
 * - "Location is off · turn it on to keep recording" when the user turned location off;
 * - "Waiting for GPS…" before the first fix;
 * - "4,631 km to go · ETA 03:51 EST" with a fix from the last 10 s (no ETA below 50 km/h);
 * - "Estimated position · last GPS fix 4 min ago" after that, while [DeadReckoning] can estimate;
 * - "Last GPS fix 4 min ago" when it can't (no speed, or more than 3 hours).
 *
 * [millisSinceFix] is the age of [lastFix] on a monotonic clock; [nowMillis] is the wall clock,
 * for the ETA's "+1" day.
 */
fun trackingNotificationText(
    flight: TrackedFlight,
    providerEnabled: Boolean,
    lastFix: Position?,
    millisSinceFix: Long,
    nowMillis: Long,
    locale: Locale,
): NotificationText {
    val title = "Tracking ${flight.codes.origin} → ${flight.codes.destination}"
    val text = when {
        !providerEnabled -> "Location is off · turn it on to keep recording"
        lastFix == null -> "Waiting for GPS…"
        DeadReckoning.isStale(millisSinceFix) ->
            if (DeadReckoning.estimate(flight.destination, lastFix, millisSinceFix) != null) {
                estimatedPositionLine(millisSinceFix)
            } else {
                "Last GPS fix ${formatFixAge(millisSinceFix)} ago"
            }
        else -> {
            val stats = FlightStatsCalculator.compute(flight.origin, flight.destination, lastFix)
            val toGo = "${formatDistance(stats.distanceRemainingMeters, locale)} to go"
            val eta = formatEtaOrUtc(stats.etaMillis, nowMillis, flight.destinationZone, locale)
            // A UTC fallback ends in "UTC" itself, so it needs no label.
            if (eta.text == UNKNOWN) toGo else "$toGo · ETA ${eta.text}"
        }
    }
    return NotificationText(title, text)
}
