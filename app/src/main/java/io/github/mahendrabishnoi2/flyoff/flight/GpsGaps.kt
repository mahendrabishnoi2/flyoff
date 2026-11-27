package io.github.mahendrabishnoi2.flyoff.flight

/**
 * The simulator's fake GPS drop-outs, so dead reckoning can be tested on the ground.
 *
 * Two gaps, both in the cruise of any route long enough to have one: a long one like a stretch
 * with no sky view, and a shorter one later. On the simulator's LHR to JFK (6.4 hours) they last
 * 38 and 27 minutes, well inside [DeadReckoning.MAX_ESTIMATE_MILLIS].
 */
object GpsGaps {

    /** Each gap as fractions of the flight's duration: start inclusive, end exclusive. */
    private val GAPS = listOf(0.30 to 0.40, 0.65 to 0.72)

    /**
     * True during a simulated drop-out: from 30 % to 40 % and from 65 % to 72 % of the flight's
     * duration (start inclusive, end exclusive). False for durationSeconds <= 0.
     */
    fun inGap(elapsedSeconds: Double, durationSeconds: Double): Boolean {
        if (!(durationSeconds > 0.0)) return false
        val fraction = elapsedSeconds / durationSeconds
        return GAPS.any { (start, end) -> fraction >= start && fraction < end }
    }
}
