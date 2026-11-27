package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A web-mercator map tile: column [x] (0 at 180° W, growing east) and row [y] (0 at the north
 * edge, growing south) of the 2^[z] × 2^[z] grid at zoom [z]. This is the XYZ scheme that
 * OpenFreeMap and MapLibre use.
 */
data class TileId(val z: Int, val x: Int, val y: Int) {
    init {
        require(z in 0..MAX_ZOOM) { "zoom $z is not in 0..$MAX_ZOOM" }
        val n = 1 shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y is outside the $n × $n grid" }
    }

    companion object {
        /** The highest zoom a [TileId] may have. */
        const val MAX_ZOOM: Int = 22
    }
}

/** A tile's edges in degrees: longitudes [west] < [east], latitudes [south] < [north]. */
data class TileBounds(val west: Double, val south: Double, val east: Double, val north: Double)

/**
 * The spherical web-mercator projection behind XYZ map tiles.
 *
 * [x] and [y] are "world" coordinates in 0..1: x grows east from 180° W, y grows south from the
 * map's north edge at [MAX_LATITUDE]. Multiply by 2^z to get tile coordinates at zoom z.
 */
object WebMercator {

    /** The latitude of the map's north edge (the south edge is its negative): atan(sinh π), in degrees. */
    const val MAX_LATITUDE: Double = 85.05112877980659

    /** World x in 0..1 of [longitude], which is taken modulo 360 (180° E and 180° W both give 0). */
    fun x(longitude: Double): Double {
        val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0
        return wrapped / 360.0
    }

    /** World y in 0..1 of [latitude], clamped to ±[MAX_LATITUDE] (so 0 at the north edge, 1 at the south). */
    fun y(latitude: Double): Double {
        val phi = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        val y = (1.0 - ln(tan(PI / 4 + phi / 2)) / PI) / 2.0
        return y.coerceIn(0.0, 1.0)
    }

    /** The latitude in degrees at world [y] (0..1); the inverse of [y]. */
    fun latitude(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /** The tile at [zoom] that contains [point]. Points beyond ±[MAX_LATITUDE] fall in the edge rows. */
    fun tileAt(point: LatLon, zoom: Int): TileId {
        val n = 1 shl zoom
        val x = floor(x(point.longitude) * n).toInt().coerceIn(0, n - 1)
        val y = floor(y(point.latitude) * n).toInt().coerceIn(0, n - 1)
        return TileId(zoom, x, y)
    }

    /** The edges of [tile] in degrees. */
    fun bounds(tile: TileId): TileBounds {
        val n = (1 shl tile.z).toDouble()
        return TileBounds(
            west = tile.x / n * 360.0 - 180.0,
            south = latitude((tile.y + 1) / n),
            east = (tile.x + 1) / n * 360.0 - 180.0,
            north = latitude(tile.y / n),
        )
    }
}
