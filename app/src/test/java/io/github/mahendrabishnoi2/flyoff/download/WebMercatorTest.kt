package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/**
 * Reference tiles come from the OpenStreetMap wiki's "slippy map tilenames" formula
 * (`x = ⌊(lon + 180) / 360 · 2^z⌋`, `y = ⌊(1 − asinh(tan φ) / π) / 2 · 2^z⌋`), computed in Python.
 * Airport coordinates are from OurAirports (public domain).
 */
class WebMercatorTest {

    private val lhr = LatLon(51.4707, -0.4599)
    private val jfk = LatLon(40.6394, -73.7793)
    private val syd = LatLon(-33.9461, 151.1770)

    @Test
    fun `airports fall in the reference tiles`() {
        assertEquals(TileId(6, 31, 21), WebMercator.tileAt(lhr, 6))
        assertEquals(TileId(8, 127, 85), WebMercator.tileAt(lhr, 8))
        assertEquals(TileId(9, 255, 170), WebMercator.tileAt(lhr, 9))
        assertEquals(TileId(8, 75, 96), WebMercator.tileAt(jfk, 8))
        assertEquals(TileId(9, 471, 307), WebMercator.tileAt(syd, 9))
    }

    @Test
    fun `the antimeridian belongs to the westernmost column`() {
        assertEquals(0, WebMercator.tileAt(LatLon(0.5, 180.0), 3).x)
        assertEquals(0, WebMercator.tileAt(LatLon(0.5, -180.0), 3).x)
        assertEquals(7, WebMercator.tileAt(LatLon(0.5, 179.999), 3).x)
    }

    @Test
    fun `unwrapped longitudes give the same tile`() {
        assertEquals(WebMercator.tileAt(LatLon(37.6, -122.4), 8), WebMercator.tileAt(LatLon(37.6, 237.6), 8))
        assertEquals(WebMercator.tileAt(LatLon(37.6, -122.4), 8), WebMercator.tileAt(LatLon(37.6, -482.4), 8))
    }

    @Test
    fun `latitudes beyond the mercator limit clamp to the edge rows`() {
        assertEquals(TileId(6, 32, 0), WebMercator.tileAt(LatLon(89.0, 0.0), 6))
        assertEquals(TileId(6, 32, 0), WebMercator.tileAt(LatLon(90.0, 0.0), 6))
        assertEquals(TileId(6, 32, 63), WebMercator.tileAt(LatLon(-90.0, 0.0), 6))
    }

    @Test
    fun `world tile spans the whole mercator square`() {
        val b = WebMercator.bounds(TileId(0, 0, 0))
        assertEquals(-180.0, b.west, 0.0)
        assertEquals(180.0, b.east, 0.0)
        assertEquals(WebMercator.MAX_LATITUDE, b.north, 1e-9)
        assertEquals(-WebMercator.MAX_LATITUDE, b.south, 1e-9)
    }

    @Test
    fun `tile bounds match the reference`() {
        // Python: latitude of row 85 at zoom 8 is 51.61801654877, of row 86 50.73645513701.
        val b = WebMercator.bounds(TileId(8, 127, 85))
        assertEquals(-1.40625, b.west, 1e-12)
        assertEquals(0.0, b.east, 1e-12)
        assertEquals(51.6180165487737, b.north, 1e-9)
        assertEquals(50.73645513701065, b.south, 1e-9)
    }

    @Test
    fun `the centre of a tile lies in that tile`() {
        val random = Random(7)
        repeat(500) {
            val z = random.nextInt(0, 15)
            val n = 1 shl z
            val tile = TileId(z, random.nextInt(n), random.nextInt(n))
            val b = WebMercator.bounds(tile)
            val centre = LatLon(WebMercator.latitude((WebMercator.y(b.north) + WebMercator.y(b.south)) / 2), (b.west + b.east) / 2)
            assertEquals(tile, WebMercator.tileAt(centre, z))
        }
    }

    @Test
    fun `y and latitude are inverses`() {
        for (lat in listOf(-85.0, -60.0, -1.0, 0.0, 33.3, 51.47, 80.0, 85.0)) {
            assertEquals(lat, WebMercator.latitude(WebMercator.y(lat)), 1e-9)
        }
        assertEquals(0.5, WebMercator.y(0.0), 1e-15)
        assertEquals(0.0, WebMercator.y(90.0), 1e-12)
        assertEquals(1.0, WebMercator.y(-90.0), 1e-12)
    }

    @Test
    fun `invalid tiles are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TileId(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileId(2, 4, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileId(2, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { TileId(TileId.MAX_ZOOM + 1, 0, 0) }
    }
}
