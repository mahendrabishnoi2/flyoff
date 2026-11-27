package io.github.mahendrabishnoi2.flyoff.geo

/**
 * A point on the Earth in degrees.
 *
 * [latitude] must be in -90..90. [longitude] is normally in -180..180, but any finite value is
 * accepted and means the same meridian modulo 360: [Geodesy.greatCirclePoints] returns unwrapped
 * longitudes (for example 237.6 for San Francisco) so a route across the antimeridian draws
 * without a jump. Every [Geodesy] function accepts such longitudes.
 */
data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude $latitude is not in -90..90" }
        require(longitude.isFinite()) { "longitude $longitude is not a finite number" }
    }
}
