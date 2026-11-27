package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.abs
import kotlin.math.min

/** Airports (OurAirports, public domain) and test routes shared by the download tests. */
object Routes {
    val LHR = LatLon(51.4707, -0.4599)
    val JFK = LatLon(40.6394, -73.7793)
    val CDG = LatLon(49.0128, 2.55)
    val FRA = LatLon(50.0267, 8.5584)
    val DEL = LatLon(28.5556, 77.0952)
    val SYD = LatLon(-33.9461, 151.1770)
    val LAX = LatLon(33.9425, -118.4080)
    val NRT = LatLon(35.7686, 140.3887)
    val SFO = LatLon(37.6198, -122.3748)
    val SIN = LatLon(1.3502, 103.994)

    /** Both ends at 60° N, 144.4° of longitude apart: the great circle peaks at 80° N over 0° E. */
    val POLAR_80_FROM = LatLon(60.0, -72.2)
    val POLAR_80_TO = LatLon(60.0, 72.2)

    /** 180° of longitude apart at 70° N: the great circle runs straight over the North Pole. */
    val OVER_POLE_FROM = LatLon(70.0, 0.0)
    val OVER_POLE_TO = LatLon(70.0, 180.0)
}

/**
 * Distance from [p] to the great-circle segment [a]–[b]: the cross-track distance where the foot
 * of the perpendicular lies on the segment, else the distance to the nearer end. An independent
 * check built only on [Geodesy]'s tested primitives.
 */
fun distanceToSegment(a: LatLon, b: LatLon, p: LatLon): Double {
    val length = Geodesy.distanceMeters(a, b)
    if (length < 1.0) return Geodesy.distanceMeters(a, p)
    val along = Geodesy.alongTrackMeters(a, b, p)
    val toEnds = min(Geodesy.distanceMeters(a, p), Geodesy.distanceMeters(b, p))
    return if (along in 0.0..length) min(abs(Geodesy.crossTrackMeters(a, b, p)), toEnds) else toEnds
}

/** The point [alongMeters] from [a] towards [b], moved [sideMeters] to the right (negative: left). */
fun offsetPoint(a: LatLon, b: LatLon, alongMeters: Double, sideMeters: Double): LatLon {
    val length = Geodesy.distanceMeters(a, b)
    val p = Geodesy.interpolate(a, b, (alongMeters / length).coerceIn(0.0, 1.0))
    val course = if (Geodesy.distanceMeters(p, b) > 1.0) {
        Geodesy.initialBearingDegrees(p, b)
    } else {
        Geodesy.initialBearingDegrees(b, a) + 180.0
    }
    return if (sideMeters == 0.0) p else Geodesy.destination(p, course + if (sideMeters > 0) 90.0 else -90.0, abs(sideMeters))
}

/**
 * True when [point] is inside any of [polygons] (closed lon/lat rings), testing in web-mercator
 * world coordinates, where MapLibre draws polygon edges as straight lines. Even-odd rule.
 */
fun insideAny(polygons: List<List<LatLon>>, point: LatLon): Boolean {
    val px = WebMercator.x(point.longitude) * 360.0 - 180.0
    val py = WebMercator.y(point.latitude)
    return polygons.any { ring ->
        var inside = false
        for (i in 0 until ring.size - 1) {
            val x0 = ring[i].longitude
            val y0 = WebMercator.y(ring[i].latitude)
            val x1 = ring[i + 1].longitude
            val y1 = WebMercator.y(ring[i + 1].latitude)
            if ((y0 > py) != (y1 > py)) {
                val x = x0 + (py - y0) / (y1 - y0) * (x1 - x0)
                if (x > px) inside = !inside
            }
        }
        inside
    }
}

/** The midpoint of the straight mercator edge from [p] to [q], back in lon/lat. */
fun mercatorMidpoint(p: LatLon, q: LatLon): LatLon = LatLon(
    WebMercator.latitude((WebMercator.y(p.latitude) + WebMercator.y(q.latitude)) / 2),
    (p.longitude + q.longitude) / 2,
)

/** Shoelace area in degrees² of a closed ring, positive when counter-clockwise (east = +x, north = +y). */
fun signedArea(ring: List<LatLon>): Double {
    var sum = 0.0
    for (i in 0 until ring.size - 1) {
        sum += ring[i].longitude * ring[i + 1].latitude - ring[i + 1].longitude * ring[i].latitude
    }
    return sum / 2
}
