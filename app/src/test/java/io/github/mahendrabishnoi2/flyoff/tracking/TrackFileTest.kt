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

    private fun point(i: Int) = TrackPoint(1_767_225_600_000L + i * 5_000L, LatLon(51.47, -0.45 + i * 0.01), 10_000.0, 231.5)

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
        assertEquals(emptyList<TrackPoint>(), track.open(lhrJfk))
        track.append(point(0))
        track.append(point(1))
        assertEquals(listOf(point(0), point(1)), track.read(lhrJfk))
        assertEquals("# Flyoff track LHR JFK", file.readLines().first())
    }

    @Test
    fun theSameFlightContinues() {
        val (_, track) = newFile()
        track.open(lhrJfk)
        track.append(point(0))
        // The app was killed and tracking started again for the same flight.
        val again = TrackFile(File(folder.root, "track.log"))
        assertEquals(listOf(point(0)), again.open(lhrJfk))
        again.append(point(1))
        assertEquals(listOf(point(0), point(1)), again.read(lhrJfk))
    }

    @Test
    fun anotherFlightStartsFresh() {
        val (file, track) = newFile()
        track.open(lhrJfk)
        track.append(point(0))
        assertEquals(emptyList<TrackPoint>(), track.read(sydLax))
        assertEquals(emptyList<TrackPoint>(), track.open(sydLax))
        assertEquals(listOf("# Flyoff track SYD LAX"), file.readLines())
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk))
    }

    @Test
    fun missingOrHeaderlessFileReadsEmpty() {
        val (file, track) = newFile()
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk))
        file.writeText("1767225600000,51.47000,-0.45000,10000.0,231.5\n")
        assertEquals(emptyList<TrackPoint>(), track.read(lhrJfk))
        assertEquals(emptyList<TrackPoint>(), track.open(lhrJfk))
        assertEquals(listOf("# Flyoff track LHR JFK"), file.readLines())
    }

    @Test
    fun badLinesAreSkipped() {
        val (file, track) = newFile()
        file.writeText("# Flyoff track LHR JFK\nnonsense\n1767225600000,51.47000,-0.45000,10000.0,231.5\n\n")
        assertEquals(listOf(point(0)), track.read(lhrJfk))
    }

    @Test
    fun aHalfWrittenLastLineIsDroppedAndCutOff() {
        val (file, track) = newFile()
        // Killed inside the speed field: "231" still parses, so only the missing newline shows it.
        file.writeText("# Flyoff track LHR JFK\n1767225600000,51.47000,-0.45000,10000.0,231.5\n1767225605000,51.47000,-0.44000,10000.0,231")
        assertEquals(listOf(point(0)), track.read(lhrJfk))
        assertEquals(listOf(point(0)), track.open(lhrJfk))
        track.append(point(1))
        assertEquals(listOf(point(0), point(1)), track.read(lhrJfk))
        assertEquals(3, file.readLines().size)
    }

    @Test
    fun deleteRemovesTheFile() {
        val (file, track) = newFile()
        track.open(lhrJfk)
        track.delete()
        assertFalse(file.exists())
    }
}
