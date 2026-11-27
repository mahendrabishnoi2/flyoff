package io.github.mahendrabishnoi2.flyoff.location

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Satellite counts from the latest `GnssStatus`. */
data class Satellites(val inView: Int, val usedInFix: Int)

/** Whether the GPS has a fix good enough to set off with: the "GPS ready" check before take-off. */
sealed interface GpsReadiness {
    /** No fix in the last [FRESH_FIX_MILLIS]. */
    data class Searching(val satellitesInView: Int?) : GpsReadiness

    /** A recent fix within [READY_ACCURACY_METERS] (or with no accuracy reported). */
    data class Ready(val accuracyMeters: Float?, val satellitesUsed: Int?) : GpsReadiness

    /** A recent fix, but too rough to trust yet. */
    data class Weak(val accuracyMeters: Float, val satellitesUsed: Int?) : GpsReadiness
}

/** A fix older than this doesn't count: the same 10 s after which the dot becomes an estimate. */
const val FRESH_FIX_MILLIS = 10_000L

/** A fix rougher than this is "weak". Open sky usually gives 3–10 m; a plane window 10–30 m. */
const val READY_ACCURACY_METERS = 50f

/**
 * The readiness from the latest fix's age ([fixAgeMillis], null without a fix) and accuracy, and
 * the latest satellite counts ([satellites], null when the phone hasn't reported any).
 */
fun gpsReadiness(fixAgeMillis: Long?, accuracyMeters: Float?, satellites: Satellites?): GpsReadiness {
    if (fixAgeMillis == null || fixAgeMillis !in 0 until FRESH_FIX_MILLIS) {
        return GpsReadiness.Searching(satellites?.inView)
    }
    val used = satellites?.usedInFix?.takeIf { it > 0 }
    return if (accuracyMeters != null && accuracyMeters > READY_ACCURACY_METERS) {
        GpsReadiness.Weak(accuracyMeters, used)
    } else {
        GpsReadiness.Ready(accuracyMeters, used)
    }
}

/**
 * One line such as "Searching for GPS · 4 satellites in view" or "GPS ready · ±6 m · 9 satellites".
 * Values that aren't known are left out. Numbers use [locale]'s digits, as in [summary].
 */
fun GpsReadiness.text(locale: Locale): String {
    val number = NumberFormat.getIntegerInstance(locale)
    fun satellites(count: Int, suffix: String) =
        "${number.format(count)} ${if (count == 1) "satellite" else "satellites"}$suffix"
    fun accuracy(meters: Float) = "±${number.format(meters.roundToInt())} m"
    return when (this) {
        is GpsReadiness.Searching ->
            if (satellitesInView == null || satellitesInView <= 0) {
                "Searching for GPS…"
            } else {
                "Searching for GPS · ${satellites(satellitesInView, " in view")}"
            }
        is GpsReadiness.Ready -> listOfNotNull(
            "GPS ready",
            accuracyMeters?.let(::accuracy),
            satellitesUsed?.let { satellites(it, "") },
        ).joinToString(" · ")
        is GpsReadiness.Weak -> listOfNotNull(
            "Weak GPS signal",
            accuracy(accuracyMeters),
            satellitesUsed?.let { satellites(it, "") },
        ).joinToString(" · ")
    }
}
