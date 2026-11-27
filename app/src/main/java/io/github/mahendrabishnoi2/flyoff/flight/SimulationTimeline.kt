package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy

/**
 * Where the simulator shows the plane. Until the replay is first started ([takeoffMillis] null)
 * it is parked at the origin: on the ground, speed 0, facing along the route, at wall-clock time
 * [wallMillis], so the stats show no take-off speed or arrival estimate while it waits. After
 * that it is [flight]'s position [timeline]'s elapsed time after the take-off at [takeoffMillis].
 */
fun simulatorPosition(
    flight: SimulatedFlight,
    timeline: SimulationTimeline,
    takeoffMillis: Long?,
    clockMillis: Long,
    wallMillis: Long,
): Position {
    if (takeoffMillis == null) {
        return Position(
            latLon = flight.origin,
            altitudeMeters = 0.0,
            speedMetersPerSecond = 0.0,
            bearingDegrees = Geodesy.initialBearingDegrees(flight.origin, flight.destination),
            timeMillis = wallMillis,
        )
    }
    return flight.positionAt(timeline.elapsedSeconds(clockMillis), takeoffMillis)
}

/**
 * The flight simulator's clock: how far into a [SimulatedFlight] of [durationSeconds] the replay
 * is. Simulated time runs at [speed] times real time while [running], stands still while paused,
 * and stops at [durationSeconds] (touchdown).
 *
 * It is an immutable value: every change returns a new timeline. The current time is always
 * passed in, read from a monotonic clock (`SystemClock.elapsedRealtime()` in the app, any number
 * in tests), so tests control time completely. The value is small and plain, so the app can save
 * it across rotation and process death; the replay then carries on as if it had kept running.
 *
 * @property elapsedAtAnchor simulated seconds reached at [anchorMillis].
 * @property anchorMillis the clock reading when the timeline last changed.
 */
data class SimulationTimeline(
    val durationSeconds: Double,
    val speed: Int,
    val running: Boolean,
    val elapsedAtAnchor: Double,
    val anchorMillis: Long,
) {
    init {
        require(durationSeconds.isFinite() && durationSeconds >= 0.0) { "duration $durationSeconds must be ≥ 0" }
        require(speed > 0) { "speed $speed must be positive" }
        require(elapsedAtAnchor in 0.0..durationSeconds) { "elapsed $elapsedAtAnchor is not in 0..$durationSeconds" }
    }

    /**
     * Simulated seconds since take-off at clock reading [nowMillis], in 0..[durationSeconds].
     * A clock reading before [anchorMillis] (the monotonic clock restarts on reboot) adds no time.
     */
    fun elapsedSeconds(nowMillis: Long): Double {
        if (!running) return elapsedAtAnchor
        val realSeconds = (nowMillis - anchorMillis).coerceAtLeast(0L) / 1000.0
        return (elapsedAtAnchor + realSeconds * speed).coerceAtMost(durationSeconds)
    }

    /** True once the replay has reached touchdown. */
    fun isFinished(nowMillis: Long): Boolean = elapsedSeconds(nowMillis) >= durationSeconds

    /** Runs from where it is; a finished replay starts again from take-off. */
    fun started(nowMillis: Long): SimulationTimeline {
        val from = if (isFinished(nowMillis)) 0.0 else elapsedSeconds(nowMillis)
        return copy(running = true, elapsedAtAnchor = from, anchorMillis = nowMillis)
    }

    /** Stands still where it is. */
    fun paused(nowMillis: Long): SimulationTimeline =
        copy(running = false, elapsedAtAnchor = elapsedSeconds(nowMillis), anchorMillis = nowMillis)

    /** Goes on at [newSpeed] from where it is, running or paused as before. */
    fun withSpeed(newSpeed: Int, nowMillis: Long): SimulationTimeline =
        copy(speed = newSpeed, elapsedAtAnchor = elapsedSeconds(nowMillis), anchorMillis = nowMillis)

    companion object {
        /** The speeds the simulator offers, as multiples of real time. */
        val SPEEDS = listOf(1, 10, 60, 300)

        /** 60×: a transatlantic flight takes about 7 minutes. */
        const val DEFAULT_SPEED = 60

        /** A replay paused at take-off, waiting to be started. */
        fun ready(durationSeconds: Double, speed: Int, nowMillis: Long) = SimulationTimeline(
            durationSeconds = durationSeconds,
            speed = speed,
            running = false,
            elapsedAtAnchor = 0.0,
            anchorMillis = nowMillis,
        )
    }
}
