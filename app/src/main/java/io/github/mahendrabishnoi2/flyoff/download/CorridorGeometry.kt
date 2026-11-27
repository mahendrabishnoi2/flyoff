package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.geo.unwrapLongitudes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * The strip of map to download along a flight: every point within a half-width of the
 * great-circle route, as polygons MapLibre's offline downloader accepts.
 *
 * The result is a multipolygon: a list of polygons, each given by its outer ring (no holes).
 * Each ring is closed (its last point equals its first) and counter-clockwise, its longitudes are
 * in [-180, 180] and its latitudes within ±[WebMercator.MAX_LATITUDE], where map tiles stop.
 * MapLibre joins consecutive points with straight lines on the web-mercator map, so the rings
 * are dense enough (a point every [VERTEX_SPACING_METERS] along the sides, every 10° around the
 * ends) that those lines stay within about 1 km of the true buffer.
 *
 * - **Ends:** round at the airports (a half circle of the half-width, so the approach from any
 *   side is on the map); square where a strip is cut short of an airport, so that consecutive
 *   segments of one route butt together.
 * - **Antimeridian:** a strip that crosses 180° is cut there into one polygon on each side.
 * - **Poles:** a strip that contains a pole (a route passing within the half-width of it) is
 *   closed along the top or bottom edge of the map, so it covers the whole polar cap of the map.
 */
object CorridorGeometry {

    /** Half the width of the default strip: 175 km either side of the route, 350 km in all. */
    const val DEFAULT_HALF_WIDTH_METERS: Double = 175_000.0

    /** The widest half-width accepted. */
    const val MAX_HALF_WIDTH_METERS: Double = 1_000_000.0

    /** The largest along-route distance between consecutive points on a strip's sides. */
    const val VERTEX_SPACING_METERS: Double = 20_000.0

    /** Points per half circle at a round end (10° apart: each chord is within 0.7 km of the arc). */
    private const val CAP_STEPS = 18

    /** Points across a square end (about 22 km apart for the default width). */
    private const val SQUARE_END_STEPS = 16

    /** Ends closer than this to an airport count as at the airport. */
    private const val AT_AIRPORT_METERS = 1.0

    /**
     * The strip within [halfWidthMeters] of the great-circle route from [origin] to [destination],
     * between [fromMeters] and [toMeters] along it (the whole route by default).
     *
     * Requires 0 ≤ [fromMeters] < [toMeters] ≤ the route length and a half-width in
     * (0, [MAX_HALF_WIDTH_METERS]]. When [origin] and [destination] are the same point the strip
     * is the circle of [halfWidthMeters] around it, and the range is ignored. Throws
     * [IllegalArgumentException] for antipodal airports, which no single great circle joins.
     */
    fun strip(
        origin: LatLon,
        destination: LatLon,
        halfWidthMeters: Double = DEFAULT_HALF_WIDTH_METERS,
        fromMeters: Double = 0.0,
        toMeters: Double = Geodesy.distanceMeters(origin, destination),
    ): List<List<LatLon>> {
        require(halfWidthMeters > 0.0 && halfWidthMeters <= MAX_HALF_WIDTH_METERS) {
            "half-width $halfWidthMeters m is not in (0, $MAX_HALF_WIDTH_METERS]"
        }
        val length = Geodesy.distanceMeters(origin, destination)
        if (length < AT_AIRPORT_METERS) {
            val circle = (0 until 2 * CAP_STEPS).map { Geodesy.destination(origin, it * 180.0 / CAP_STEPS, halfWidthMeters) }
            return polygons(circle, listOf(origin))
        }
        require(Geodesy.hasTrack(origin, destination)) { "$origin and $destination are antipodal: no single route joins them" }
        require(fromMeters >= 0.0 && fromMeters < toMeters && toMeters <= length + AT_AIRPORT_METERS) {
            "range $fromMeters..$toMeters m is not within the route's 0..$length m"
        }

        val steps = max(1, ceil((toMeters - fromMeters) / VERTEX_SPACING_METERS).toInt())
        val centres = (0..steps).map { i ->
            val along = fromMeters + (toMeters - fromMeters) * i / steps
            Geodesy.interpolate(origin, destination, (along / length).coerceIn(0.0, 1.0))
        }
        val backCourse = Geodesy.initialBearingDegrees(destination, origin) + 180.0
        val courses = centres.map { c ->
            if (Geodesy.distanceMeters(c, destination) > AT_AIRPORT_METERS) Geodesy.initialBearingDegrees(c, destination) else backCourse
        }

        // Clockwise: round (or across) the start from the right side to the left, forward along
        // the left side, round (or across) the end to the right side, and back along it.
        val ring = ArrayList<LatLon>()
        val start = centres.first()
        if (fromMeters < AT_AIRPORT_METERS) {
            ring += arc(start, courses.first() + 90.0, halfWidthMeters)
        } else {
            ring += across(start, courses.first(), halfWidthMeters, -halfWidthMeters)
        }
        for (i in 1 until steps) ring += Geodesy.destination(centres[i], courses[i] - 90.0, halfWidthMeters)
        val end = centres.last()
        if (toMeters > length - AT_AIRPORT_METERS) {
            ring += arc(end, courses.last() - 90.0, halfWidthMeters)
        } else {
            ring += across(end, courses.last(), -halfWidthMeters, halfWidthMeters)
        }
        for (i in steps - 1 downTo 1) ring += Geodesy.destination(centres[i], courses[i] + 90.0, halfWidthMeters)
        return polygons(ring, centres)
    }

    /** The half circle of [radius] around [centre], from bearing [fromBearing] clockwise to [fromBearing] + 180. */
    private fun arc(centre: LatLon, fromBearing: Double, radius: Double): List<LatLon> =
        (0..CAP_STEPS).map { Geodesy.destination(centre, fromBearing + it * 180.0 / CAP_STEPS, radius) }

    /** Points across the route at [centre] (flown on [course]) from [fromSide] to [toSide] metres right of it. */
    private fun across(centre: LatLon, course: Double, fromSide: Double, toSide: Double): List<LatLon> =
        (0..SQUARE_END_STEPS).map {
            val side = fromSide + (toSide - fromSide) * it / SQUARE_END_STEPS
            if (side >= 0.0) Geodesy.destination(centre, course + 90.0, side) else Geodesy.destination(centre, course - 90.0, -side)
        }

    /**
     * A point in "unwrapped mercator" coordinates: [x] is the unwrapped longitude in degrees, [y]
     * the world y, which is outside 0..1 beyond the edges of the map.
     */
    private data class Point(val x: Double, val y: Double)

    /** World y of [latitude] without the map's clamp, kept finite: -1 and 2 are beyond 89.99° N and S. */
    private fun rawY(latitude: Double): Double {
        val phi = Math.toRadians(latitude.coerceIn(-89.999999, 89.999999))
        return ((1.0 - ln(tan(PI / 4 + phi / 2)) / PI) / 2.0).coerceIn(-1.0, 2.0)
    }

    /**
     * Turns a ring of points on the sphere (not closed) into polygons in [-180, 180], cut at the
     * antimeridian, closed over a pole that it winds around, and cut at the top and bottom edges
     * of the map. [centres] are points on the route, used to tell which pole that is.
     */
    private fun polygons(ring: List<LatLon>, centres: List<LatLon>): List<List<LatLon>> {
        val unwrapped = unwrapLongitudes(ring)
        val points = unwrapped.mapTo(ArrayList()) { Point(it.longitude, rawY(it.latitude)) }
        val closing = unwrapLongitudes(listOf(unwrapped.last(), ring.first())).last().longitude
        if (abs(closing - unwrapped.first().longitude) > 180.0) {
            // The ring goes once round a pole: close it beyond that edge of the map instead.
            val north = centres.minOf { 90.0 - it.latitude } <= centres.minOf { it.latitude + 90.0 }
            val edge = if (north) -1.0 else 2.0
            points += Point(points.last().x, edge)
            points += Point(points.first().x, edge)
        }

        val minBand = floor((points.minOf { it.x } + 180.0) / 360.0).toInt()
        val maxBand = floor((points.maxOf { it.x } + 180.0) / 360.0).toInt()
        val result = ArrayList<List<LatLon>>()
        for (band in minBand..maxBand) {
            val west = band * 360.0 - 180.0
            val east = band * 360.0 + 180.0
            val piece = clip(clip(points, west, keepEast = true), east, keepEast = false)
                .let { clipY(clipY(it, 0.0, keepSouth = true), 1.0, keepSouth = false) }
                .map { Point(it.x - band * 360.0, it.y) }
                .let(::withoutRepeats)
            if (piece.size < 3) continue
            val area = area(piece)
            if (abs(area) < 1e-12) continue
            val ccw = if (area > 0) piece else piece.reversed()
            result += (ccw + ccw.first()).map { LatLon(latitudeOf(it.y), it.x.coerceIn(-180.0, 180.0)) }
        }
        return result
    }

    /** Sutherland–Hodgman: the part of [polygon] east (or west) of the meridian x = [line]. */
    private fun clip(polygon: List<Point>, line: Double, keepEast: Boolean): List<Point> {
        fun inside(p: Point) = if (keepEast) p.x >= line else p.x <= line
        fun cross(p: Point, q: Point) = Point(line, p.y + (line - p.x) / (q.x - p.x) * (q.y - p.y))
        val out = ArrayList<Point>()
        for (i in polygon.indices) {
            val current = polygon[i]
            val previous = polygon[(i + polygon.size - 1) % polygon.size]
            if (inside(current)) {
                if (!inside(previous)) out += cross(previous, current)
                out += current
            } else if (inside(previous)) {
                out += cross(previous, current)
            }
        }
        return out
    }

    /** Sutherland–Hodgman: the part of [polygon] south (or north) of world y = [line]. */
    private fun clipY(polygon: List<Point>, line: Double, keepSouth: Boolean): List<Point> {
        fun inside(p: Point) = if (keepSouth) p.y >= line else p.y <= line
        fun cross(p: Point, q: Point) = Point(p.x + (line - p.y) / (q.y - p.y) * (q.x - p.x), line)
        val out = ArrayList<Point>()
        for (i in polygon.indices) {
            val current = polygon[i]
            val previous = polygon[(i + polygon.size - 1) % polygon.size]
            if (inside(current)) {
                if (!inside(previous)) out += cross(previous, current)
                out += current
            } else if (inside(previous)) {
                out += cross(previous, current)
            }
        }
        return out
    }

    private fun withoutRepeats(points: List<Point>): List<Point> {
        val out = ArrayList<Point>(points.size)
        for (p in points) if (out.isEmpty() || out.last() != p) out += p
        while (out.size > 1 && out.first() == out.last()) out.removeAt(out.size - 1)
        return out
    }

    /** Shoelace area with north up (y flipped), so counter-clockwise on the map is positive. */
    private fun area(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.x * -q.y - q.x * -p.y
        }
        return sum / 2
    }

    private fun latitudeOf(y: Double): Double = when {
        y <= 0.0 -> WebMercator.MAX_LATITUDE
        y >= 1.0 -> -WebMercator.MAX_LATITUDE
        else -> WebMercator.latitude(y).coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)
    }
}
