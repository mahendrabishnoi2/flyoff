package io.github.mahendrabishnoi2.flyoff.flight

import kotlin.math.floor
import kotlin.math.roundToLong

/** The simulator's latest GPS fix, and how long ago it was in **simulated** time. */
data class SimulatorFix(val position: Position, val millisSinceFix: Long)

/**
 * Simulated seconds at which the simulator's latest fix was taken, [elapsedSeconds] into a flight
 * of [durationSeconds]. Normally that is now. With [gaps] on, during one of [GpsGaps]' drop-outs
 * the fixes are withheld, so it is the last whole simulated second before the gap (the simulated
 * GPS gives one fix a second). When the gap ends, fixes resume.
 */
fun simulatedFixSeconds(elapsedSeconds: Double, durationSeconds: Double, gaps: Boolean): Double {
    if (!gaps || !GpsGaps.inGap(elapsedSeconds, durationSeconds)) return elapsedSeconds
    var t = floor(elapsedSeconds)
    // A gap is at most a tenth of the flight: a few thousand steps on the longest routes.
    while (t > 0.0 && GpsGaps.inGap(t, durationSeconds)) t -= 1.0
    return t.coerceAtLeast(0.0)
}

/**
 * The simulator's latest fix: [simulatorPosition] at [simulatedFixSeconds]. Its age is simulated
 * time, so at 300× a 10-second drop-out lasts 1/30 s of real time, as dead reckoning expects.
 */
fun simulatorFix(
    flight: SimulatedFlight,
    timeline: SimulationTimeline,
    takeoffMillis: Long?,
    clockMillis: Long,
    wallMillis: Long,
    gaps: Boolean,
): SimulatorFix {
    if (takeoffMillis == null) {
        return SimulatorFix(simulatorPosition(flight, timeline, null, clockMillis, wallMillis), 0L)
    }
    val elapsed = timeline.elapsedSeconds(clockMillis)
    val fixAt = simulatedFixSeconds(elapsed, timeline.durationSeconds, gaps)
    return SimulatorFix(
        position = flight.positionAt(fixAt, takeoffMillis),
        millisSinceFix = ((elapsed - fixAt) * 1000).roundToLong(),
    )
}
