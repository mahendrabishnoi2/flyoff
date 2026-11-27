package io.github.mahendrabishnoi2.flyoff.tracking

import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.TrackPoint
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val lhrJfk = FlightCodes("LHR", "JFK")
    private val sydLax = FlightCodes("SYD", "LAX")

    private val t0 = 1_767_225_600_000L

    /** A minute after the first point. */
    private val now = t0 + 60_000L

    private fun point(i: Int) = TrackPoint(t0 + i * 5_000L, LatLon(51.47, -0.45 + i * 0.01), 10_000.0, 231.5)

    private fun newFile(): Pair<File, TrackFile> {
        val file = File(folder.root, "track.log")
        return file to TrackFile(file)
    }

    @Test
    fun headerRoundTrip() {
        assertEquals("# Flyoff track LHR JFK", TrackFileHeader.format(lhrJfk))
        assertEquals(lhrJfk, TrackFileHeader.parse("# Flyoff track LHR JFK"))
        assertNull(TrackFileHeader.parse("1767225600000,51.47000,-0.45000,10000.0,231.5"))
        assertNull(TrackFileHeader.parse("# Flyoff track LHR"))
        assertNull(TrackFileHeader.parse("# Flyoff track LHR LHR"))
        assertNull(TrackFileHeader.parse(""))
    }

    @Test
    fun recordsAndReadsBack() {
        val (file, track) = newFile()
        assertEquals(emptyList<TrackPoint>(), track.open(lhrJfk, now))
        track.append(point(0))
        track.append(point(1))
        assertEquals(listOf(point(0), point(1)), track.read(lhrJfk, now))
        assertEquals("# Flyoff track LHR JFK", file.readLines().first())
    }

    @Test
    fun theSameFlightContinues() {
        val (_, track) = newFile()
        track.open(lhrJfk, now)
        track.append(point(0))
        // The app was killed and tracking started again for the same flight.
        val again = TrackFile(File(folder.root, "track.log"))
        assertEquals(listOf(point(0)), again.open(lhrJfk, now))
        again.append(point(1))
        assertEquals(listOf(point(0), point(1)), again.read(lhrJfk, now))
    }

    @Test
    fun anotherFlightStartsFresh() {
        val (file, track) = newFile()
        track.open(lhrJfk, now)
        track.append(point(0))
        assertEquals(emptyList<TrackPoint>(), track.read(sydLax, now))
        assertEquals(emptyList<TrackPoint>(), track.open(sydLax, now))
        assertEquals(listOf("# Flyoff track SYD LAX"), file.readLines())
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk, now))
    }

    @Test
    fun missingOrHeaderlessFileReadsEmpty() {
        val (file, track) = newFile()
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk, now))
        file.writeText("1767225600000,51.47000,-0.45000,10000.0,231.5\n")
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk, now))
        assertEquals(emptyList<TrackPoint>(), track.open(lhrJfk, now))
        assertEquals(listOf("# Flyoff track LHR JFK"), file.readLines())
    }

    @Test
    fun badLinesAreSkipped() {
        val (file, track) = newFile()
        file.writeText("# Flyoff track LHR JFK\nnonsense\n1767225600000,51.47000,-0.45000,10000.0,231.5\n\n")
        assertEquals(listOf(point(0)), track.read(lhrJfk, now))
    }

    @Test
    fun aHalfWrittenLastLineIsDroppedAndCutOff() {
        val (file, track) = newFile()
        // Killed inside the speed field: "231" still parses, so only the missing newline shows it.
        file.writeText("# Flyoff track LHR JFK\n1767225600000,51.47000,-0.45000,10000.0,231.5\n1767225605000,51.47000,-0.44000,10000.0,231")
        assertEquals(listOf(point(0)), track.read(lhrJfk, now))
        assertEquals(listOf(point(0)), track.open(lhrJfk, now))
        track.append(point(1))
        assertEquals(listOf(point(0), point(1)), track.read(lhrJfk, now))
        assertEquals(3, file.readLines().size)
    }

    @Test
    fun deleteRemovesTheFile() {
        val (file, track) = newFile()
        track.open(lhrJfk, now)
        track.delete()
        assertFalse(file.exists())
    }

    @Test
    fun aTrackLastRecordedOver12HoursAgoIsAnEarlierFlight() {
        val (file, track) = newFile()
        track.open(lhrJfk, now)
        track.append(point(0))
        track.append(point(1))
        val newest = point(1).timeMillis
        // Within 12 hours of its newest point it's still this flight, even after a long gap.
        assertEquals(listOf(point(0), point(1)), track.read(lhrJfk, newest + TrackFile.MAX_GAP_MILLIS))
        // After that it is a flight on the same route, days ago: not shown, and not continued.
        val nextWeek = newest + 7 * 24 * 3_600_000L
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk, newest + TrackFile.MAX_GAP_MILLIS + 1))
        assertEquals(emptyList<TrackPoint>(), track.open(lhrJfk, nextWeek))
        assertEquals(listOf("# Flyoff track LHR JFK"), file.readLines())
    }

    @Test
    fun aClockBehindTheTrackStillContinuesIt() {
        // The phone's clock is behind GPS time: the track's points look like they are in the future.
        val (_, track) = newFile()
        track.open(lhrJfk, now)
        track.append(point(0))
        assertEquals(listOf(point(0)), track.open(lhrJfk, t0 - 3_600_000L))
    }
}
