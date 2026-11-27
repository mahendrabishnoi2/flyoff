package io.github.mahendrabishnoi2.flyoff.download

/**
 * When a downloaded corridor is deleted: [KEEP_AFTER_LANDING_MILLIS] after the flight lands.
 * "Landed" is whatever time the caller has: the planned arrival, or when tracking ended at the
 * destination.
 */
object CorridorRetention {

    /** How long a corridor is kept after landing: 3 days. */
    const val KEEP_AFTER_LANDING_MILLIS: Long = 3L * 24 * 60 * 60 * 1000

    /** When a corridor for a flight that lands at [landedAtMillis] becomes due for deletion. */
    fun deleteAtMillis(landedAtMillis: Long): Long =
        if (landedAtMillis > Long.MAX_VALUE - KEEP_AFTER_LANDING_MILLIS) Long.MAX_VALUE else landedAtMillis + KEEP_AFTER_LANDING_MILLIS

    /** True when, at [nowMillis], the corridor of a flight that landed at [landedAtMillis] should be deleted. */
    fun isDueForDeletion(landedAtMillis: Long, nowMillis: Long): Boolean = nowMillis >= deleteAtMillis(landedAtMillis)
}
