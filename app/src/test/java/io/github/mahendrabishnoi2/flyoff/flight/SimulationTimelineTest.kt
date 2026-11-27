package io.github.mahendrabishnoi2.flyoff.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class SimulationTimelineTest {

    private val duration = 20_000.0
    private val t0 = 1_000_000L

    @Test
    fun `a new timeline waits at the start`() {
        val timeline = SimulationTimeline.ready(duration, speed = 60, nowMillis = t0)
        assertFalse(timeline.running)
        assertEquals(0.0, timeline.elapsedSeconds(t0), 0.0)
        assertEquals(0.0, timeline.elapsedSeconds(t0 + 60_000), 0.0)
        assertFalse(timeline.isFinished(t0 + 60_000))
    }

    @Test
    fun `running time is real time times the speed`() {
        val timeline = SimulationTimeline.ready(duration, speed = 60, nowMillis = t0).started(t0 + 5_000)
        assertTrue(timeline.running)
        assertEquals(0.0, timeline.elapsedSeconds(t0 + 5_000), 1e-9)
        assertEquals(60.0, timeline.elapsedSeconds(t0 + 6_000), 1e-9)
        assertEquals(600.0, timeline.elapsedSeconds(t0 + 15_000), 1e-9)
    }

    @Test
    fun `pausing freezes the time and resuming carries on from there`() {
        val running = SimulationTimeline.ready(duration, speed = 10, nowMillis = t0).started(t0)
        val paused = running.paused(t0 + 3_000)
        assertFalse(paused.running)
        assertEquals(30.0, paused.elapsedSeconds(t0 + 3_000), 1e-9)
        assertEquals(30.0, paused.elapsedSeconds(t0 + 100_000), 1e-9)

        val resumed = paused.started(t0 + 100_000)
        assertEquals(30.0, resumed.elapsedSeconds(t0 + 100_000), 1e-9)
        assertEquals(50.0, resumed.elapsedSeconds(t0 + 102_000), 1e-9)
    }

    @Test
    fun `changing speed keeps the time reached so far`() {
        val running = SimulationTimeline.ready(duration, speed = 1, nowMillis = t0).started(t0)
        val faster = running.withSpeed(300, t0 + 10_000)
        assertEquals(300, faster.speed)
        assertEquals(10.0, faster.elapsedSeconds(t0 + 10_000), 1e-9)
        assertEquals(310.0, faster.elapsedSeconds(t0 + 11_000), 1e-9)

        val pausedFaster = running.paused(t0 + 2_000).withSpeed(60, t0 + 5_000)
        assertFalse(pausedFaster.running)
        assertEquals(2.0, pausedFaster.elapsedSeconds(t0 + 50_000), 1e-9)
    }

    @Test
    fun `time stops at the end of the flight`() {
        val timeline = SimulationTimeline.ready(duration, speed = 300, nowMillis = t0).started(t0)
        val end = t0 + ceil(duration / 300 * 1000).toLong()
        assertFalse(timeline.isFinished(end - 10))
        assertTrue(timeline.isFinished(end))
        assertEquals(duration, timeline.elapsedSeconds(end + 3_600_000), 0.0)
    }

    @Test
    fun `starting a finished flight replays it from the start`() {
        val finished = SimulationTimeline.ready(duration, speed = 300, nowMillis = t0).started(t0).paused(t0 + 999_999)
        assertTrue(finished.isFinished(t0 + 999_999))
        val replay = finished.started(t0 + 1_000_000)
        assertTrue(replay.running)
        assertEquals(0.0, replay.elapsedSeconds(t0 + 1_000_000), 1e-9)
        assertEquals(300.0, replay.elapsedSeconds(t0 + 1_001_000), 1e-9)
    }

    @Test
    fun `a clock that goes backwards adds no time`() {
        // After a reboot the monotonic clock starts again from zero.
        val timeline = SimulationTimeline.ready(duration, speed = 60, nowMillis = t0).started(t0).paused(t0 + 1_000).started(t0 + 1_000)
        assertEquals(60.0, timeline.elapsedSeconds(5), 1e-9)
    }

    @Test
    fun `a zero-length flight is finished at once`() {
        val timeline = SimulationTimeline.ready(0.0, speed = 60, nowMillis = t0)
        assertTrue(timeline.isFinished(t0))
        assertEquals(0.0, timeline.elapsedSeconds(t0), 0.0)
    }

    @Test
    fun `bad arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SimulationTimeline.ready(-1.0, 60, t0) }
        assertThrows(IllegalArgumentException::class.java) { SimulationTimeline.ready(Double.NaN, 60, t0) }
        assertThrows(IllegalArgumentException::class.java) { SimulationTimeline.ready(duration, 0, t0) }
        assertThrows(IllegalArgumentException::class.java) {
            SimulationTimeline.ready(duration, 60, t0).withSpeed(-5, t0)
        }
    }

    @Test
    fun `the offered speeds start at real time`() {
        assertEquals(listOf(1, 10, 60, 300), SimulationTimeline.SPEEDS)
        assertTrue(SimulationTimeline.DEFAULT_SPEED in SimulationTimeline.SPEEDS)
    }
}
