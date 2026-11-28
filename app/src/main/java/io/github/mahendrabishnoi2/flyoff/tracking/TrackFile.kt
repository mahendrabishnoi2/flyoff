package io.github.mahendrabishnoi2.flyoff.tracking

import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.TrackLog
import io.github.mahendrabishnoi2.flyoff.flight.TrackPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** The first line of a track file, naming the flight it belongs to: "# Flyoff track LHR JFK". */
object TrackFileHeader {
    private const val PREFIX = "# Flyoff track "

    fun format(codes: FlightCodes): String = "$PREFIX${codes.origin} ${codes.destination}"

    /** The flight named by [line], or null when it isn't a track header. */
    fun parse(line: String): FlightCodes? {
        if (!line.startsWith(PREFIX)) return null
        val parts = line.removePrefix(PREFIX).trim().split(' ')
        if (parts.size != 2) return null
        return FlightCodes.parse(parts[0], parts[1])
    }
}

/** What a [TrackFile] holds: the flight it belongs to and its points. */
data class TrackContents(val codes: FlightCodes, val points: List<TrackPoint>)

/**
 * The flown track of one flight, kept in [file]: a [TrackFileHeader] line, then one
 * [TrackLog] line per point. Each point is appended and the file closed at once, so if the app
 * is killed at most the point being written is lost.
 *
 * The header names the route, not the trip, so a track whose newest point is more than
 * [MAX_GAP_MILLIS] old counts as an earlier flight on the same route and is not continued.
 *
 * Plain file I/O: call it off the main thread.
 */
class TrackFile(private val file: File) {

    /**
     * The points recorded for [codes]; empty when there is no file, or it belongs to another
     * flight (another route, or one flown over [MAX_GAP_MILLIS] before [nowMillis]). Malformed
     * lines are skipped, and so is a last line without its newline (the app died while writing
     * it; it could still look valid with its last digits missing).
     */
    fun read(codes: FlightCodes, nowMillis: Long): List<TrackPoint> {
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return emptyList()
        }
        return pointsOf(text, codes, nowMillis) ?: emptyList()
    }

    /**
     * The flight the file belongs to and every point it has, whatever their age; null when there
     * is no file or it isn't a track. For the flight log, which keeps every tracked flight.
     */
    fun contents(): TrackContents? {
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return null
        }
        val lines = text.split('\n')
        val codes = TrackFileHeader.parse(lines.first()) ?: return null
        val points = if (lines.size < 2) emptyList() else lines.subList(1, lines.size - 1).mapNotNull(TrackLog::parse)
        return TrackContents(codes, points)
    }

    /**
     * Gets the file ready to record [codes] and returns the points it already has: a file for the
     * same flight is continued (after the app was killed, say), anything else is started afresh.
     */
    fun open(codes: FlightCodes, nowMillis: Long): List<TrackPoint> {
        val text = if (file.exists()) file.readText() else ""
        val points = pointsOf(text, codes, nowMillis)
        if (points == null) {
            file.writeText(TrackFileHeader.format(codes) + "\n")
            return emptyList()
        }
        // Cut off a half-written last line, so the next point starts on a line of its own.
        val complete = text.lastIndexOf('\n') + 1
        if (complete < text.length) {
            RandomAccessFile(file, "rw").use { it.setLength(text.substring(0, complete).toByteArray().size.toLong()) }
        }
        return points
    }

    /** Adds [point] at the end and closes the file again. */
    fun append(point: TrackPoint) {
        FileOutputStream(file, true).use { it.write((TrackLog.format(point) + "\n").toByteArray()) }
    }

    fun delete() {
        file.delete()
    }

    /** The points in [text] if its header names [codes] and its newest point is recent, else null. */
    private fun pointsOf(text: String, codes: FlightCodes, nowMillis: Long): List<TrackPoint>? {
        val lines = text.split('\n')
        if (TrackFileHeader.parse(lines.first()) != codes || lines.size < 2) return null
        // After the last newline comes "" for a complete file, or a half-written line: drop it.
        val points = lines.subList(1, lines.size - 1).mapNotNull(TrackLog::parse)
        val newest = points.maxOfOrNull { it.timeMillis } ?: return points
        return points.takeIf { nowMillis - newest <= MAX_GAP_MILLIS }
    }

    companion object {
        /**
         * 12 hours. Within one flight the track can pause for hours (GPS lost inside the cabin)
         * but not that long: the longest flights last about 19 hours, and a GPS fix by a window at
         * some point in any half of one is to be expected. Between two flights on the same route
         * there is the flight back and a stay, which is nearly always longer. On a short route
         * flown there, back and there again in one day the track would join up, which only costs
         * a stray line on the map.
         */
        const val MAX_GAP_MILLIS = 12 * 60 * 60 * 1_000L
    }
}
