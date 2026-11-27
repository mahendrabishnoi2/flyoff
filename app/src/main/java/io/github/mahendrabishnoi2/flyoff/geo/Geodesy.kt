package io.github.mahendrabishnoi2.flyoff.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle maths on a spherical Earth of radius [EARTH_RADIUS_M].
 *
 * The sphere is off by up to about 0.5 % from the WGS-84 ellipsoid (LHR–JFK is 5,539.7 km here
 * and 5,554.5 km on the ellipsoid). That is far below what a passenger can notice, and the GPS
 * position itself is exact, so the error only shifts the drawn route line and the distance
 * figures slightly.
 *
 * Internally every point becomes a unit vector from the Earth's centre (an "n-vector"), and
 * angles come from `atan2` of a cross and a dot product. That stays accurate for tiny distances,
 * at the poles and for nearly antipodal points, where `acos` and `asin` based formulas lose
 * precision.
 *
 * Distances are in metres, bearings in degrees clockwise from true north.
 */
object Geodesy {

    /** The IUGG mean Earth radius R1 = (2a + b) / 3 of WGS-84, in metres. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    /** Below this |a × b| (radians, about 6 µm on the ground) two points count as the same or antipodal. */
    private const val DEGENERATE = 1e-12

    /** Great-circle (shortest) distance between [a] and [b]. */
    fun distanceMeters(a: LatLon, b: LatLon): Double = angle(a.toVector(), b.toVector()) * EARTH_RADIUS_M

    /**
     * The course to set at [from] to fly the great circle to [to], in [0, 360).
     * It is 0 when the points are the same. At a pole it is measured from the meridian of
     * [from]'s longitude.
     */
    fun initialBearingDegrees(from: LatLon, to: LatLon): Double {
        val target = to.toVector()
        if (distanceMeters(from, to) < DEGENERATE * EARTH_RADIUS_M) return 0.0
        val (north, east) = from.northAndEast()
        return normalizeBearing(Math.toDegrees(atan2(target.dot(east), target.dot(north))))
    }

    /**
     * The point [fraction] of the way from [a] to [b] along the great circle (0 gives [a],
     * 1 gives [b]). For exactly antipodal points every great circle is as short; this one
     * then goes along [a]'s initial course due north (due south from the North Pole).
     */
    fun interpolate(a: LatLon, b: LatLon, fraction: Double): LatLon {
        require(fraction in 0.0..1.0) { "fraction $fraction is not in 0..1" }
        if (fraction == 0.0) return a
        if (fraction == 1.0) return b
        val va = a.toVector()
        val vb = b.toVector()
        val cross = va.cross(vb).norm()
        val dot = va.dot(vb)
        if (cross < DEGENERATE) {
            if (dot > 0) return a
            return destination(a, if (a.latitude == 90.0) 180.0 else 0.0, fraction * Math.PI * EARTH_RADIUS_M)
        }
        val delta = atan2(cross, dot)
        val sinDelta = sin(delta)
        val wa = sin((1 - fraction) * delta) / sinDelta
        val wb = sin(fraction * delta) / sinDelta
        return (va * wa + vb * wb).toLatLon()
    }

    /**
     * The point reached from [from] by flying [distanceMeters] along the great circle that
     * starts on [bearingDegrees]. The result's longitude is in [-180, 180].
     */
    fun destination(from: LatLon, bearingDegrees: Double, distanceMeters: Double): LatLon {
        val theta = Math.toRadians(bearingDegrees)
        val delta = distanceMeters / EARTH_RADIUS_M
        val start = from.toVector()
        val (north, east) = from.northAndEast()
        val direction = north * cos(theta) + east * sin(theta)
        return (start * cos(delta) + direction * sin(delta)).toLatLon()
    }

    /**
     * Signed distance from [point] to the great circle through [start] and [end]:
     * positive when [point] is to the right of the track flown from [start] to [end], negative
     * to the left. Throws [IllegalArgumentException] when [start] and [end] are the same or
     * antipodal, because then no single track joins them.
     */
    fun crossTrackMeters(start: LatLon, end: LatLon, point: LatLon): Double {
        val normal = trackNormal(start, end)
        val p = point.toVector()
        // The normal points to the left of the track, so a point on the right has a negative dot.
        return -asin(p.dot(normal).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
    }

    /**
     * Distance from [start] to the foot of the perpendicular from [point] onto the great circle
     * through [start] and [end], measured along it towards [end]. It is negative for points behind
     * [start] and larger than the route for points beyond [end], within ±πR (half the
     * circumference). Throws [IllegalArgumentException] when [start] and [end] are the same or
     * antipodal.
     */
    fun alongTrackMeters(start: LatLon, end: LatLon, point: LatLon): Double {
        val normal = trackNormal(start, end)
        val a = start.toVector()
        val p = point.toVector()
        val foot = p - normal * p.dot(normal)
        return atan2(a.cross(foot).dot(normal), a.dot(foot)) * EARTH_RADIUS_M
    }

    /**
     * The great circle from [a] to [b] as points no more than [maxSegmentMeters] apart, for
     * drawing. The first and last points are [a] and [b].
     *
     * Longitudes are unwrapped: each differs from the one before by less than 180°, so a route
     * across the antimeridian keeps counting past ±180 (Tokyo 139.8 → San Francisco 237.6)
     * and MapLibre draws it without a jump across the map.
     */
    fun greatCirclePoints(a: LatLon, b: LatLon, maxSegmentMeters: Double = 50_000.0): List<LatLon> {
        require(maxSegmentMeters > 0.0) { "maxSegmentMeters $maxSegmentMeters must be positive" }
        val segments = ceil(distanceMeters(a, b) / maxSegmentMeters).toInt().coerceAtLeast(1)
        val points = ArrayList<LatLon>(segments + 1)
        points += a
        for (i in 1..segments) {
            val next = if (i == segments) b else interpolate(a, b, i.toDouble() / segments)
            points += next.copy(longitude = unwrap(next.longitude, points.last().longitude))
        }
        return points
    }

    /** True when [start] and [end] define a single great-circle track (neither the same point nor antipodal). */
    internal fun hasTrack(start: LatLon, end: LatLon): Boolean =
        start.toVector().cross(end.toVector()).norm() >= DEGENERATE

    private fun trackNormal(start: LatLon, end: LatLon): Vector {
        val normal = start.toVector().cross(end.toVector())
        val length = normal.norm()
        require(length >= DEGENERATE) { "$start and $end are the same or antipodal: no single track joins them" }
        return normal * (1 / length)
    }

    /** Angle between two unit vectors, accurate at every size. */
    private fun angle(a: Vector, b: Vector): Double = atan2(a.cross(b).norm(), a.dot(b))

    /** [longitude] shifted by a multiple of 360° to lie within 180° of [previous]. */
    private fun unwrap(longitude: Double, previous: Double): Double {
        var lon = longitude
        while (lon - previous > 180.0) lon -= 360.0
        while (lon - previous < -180.0) lon += 360.0
        return lon
    }

    private fun normalizeBearing(degrees: Double): Double {
        val b = degrees % 360.0
        val positive = if (b < 0) b + 360.0 else b
        return if (positive >= 360.0) 0.0 else positive
    }

    private data class Vector(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vector) = Vector(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vector) = Vector(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = Vector(x * s, y * s, z * s)
        fun dot(o: Vector) = x * o.x + y * o.y + z * o.z
        fun cross(o: Vector) = Vector(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun norm() = sqrt(dot(this))

        fun toLatLon(): LatLon {
            val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y))).coerceIn(-90.0, 90.0)
            val lon = Math.toDegrees(atan2(y, x)).coerceIn(-180.0, 180.0)
            return LatLon(lat, lon)
        }
    }

    /**
     * Unit vectors pointing north and east along the surface at this point. At a pole they are
     * taken relative to the meridian of this point's longitude.
     */
    private fun LatLon.northAndEast(): Pair<Vector, Vector> {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        return Vector(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat)) to Vector(-sin(lon), cos(lon), 0.0)
    }

    private fun LatLon.toVector(): Vector {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        return Vector(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    }
}
