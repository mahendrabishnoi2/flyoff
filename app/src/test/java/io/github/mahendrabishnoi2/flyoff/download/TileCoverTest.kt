package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.FRA
import io.github.mahendrabishnoi2.flyoff.download.Routes.DEL
import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LAX
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SIN
import io.github.mahendrabishnoi2.flyoff.download.Routes.SYD
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class TileCoverTest {

    private val h = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS

    @Test
    fun `no polygons cover no tiles`() {
        assertTrue(TileCover.tiles(emptyList(), 8).isEmpty())
        assertEquals(0, TileCover.count(emptyList(), 6..8))
    }

    @Test
    fun `a box covers the tiles it overlaps`() {
        // lon -10..10, lat -10..10 at zoom 3: columns 3-4, rows 3-4.
        val box = ring(LatLon(-10.0, -10.0), LatLon(-10.0, 10.0), LatLon(10.0, 10.0), LatLon(10.0, -10.0))
        assertEquals(setOf(TileId(3, 3, 3), TileId(3, 4, 3), TileId(3, 3, 4), TileId(3, 4, 4)), TileCover.tiles(listOf(box), 3))
    }

    @Test
    fun `a polygon inside one tile covers just that tile`() {
        val b = WebMercator.bounds(TileId(8, 127, 85))
        val dx = (b.east - b.west) * 0.01
        val dy = (b.north - b.south) * 0.01
        val inner = ring(LatLon(b.south + dy, b.west + dx), LatLon(b.south + dy, b.east - dx), LatLon(b.north - dy, b.east - dx), LatLon(b.north - dy, b.west + dx))
        assertEquals(setOf(TileId(8, 127, 85)), TileCover.tiles(listOf(inner), 8))
    }

    @Test
    fun `a box larger than a tile fills its interior`() {
        // lon 0..90, lat 0..66.5 at zoom 5: columns 16-23, rows 8-15 (a solid 8 × 8 block).
        val box = ring(LatLon(0.5, 0.5), LatLon(0.5, 89.5), LatLon(66.0, 89.5), LatLon(66.0, 0.5))
        val tiles = TileCover.tiles(listOf(box), 5)
        val expected = (16..23).flatMap { x -> (WebMercator.tileAt(LatLon(66.0, 0.0), 5).y..15).map { y -> TileId(5, x, y) } }.toSet()
        assertEquals(expected, tiles)
    }

    @Test
    fun `the cover contains every tile that intersects the corridor and little else`() {
        val cases = listOf(
            Triple(LHR, JFK, 6..9),
            Triple(FRA, DEL, 6..8),
            Triple(SYD, LAX, 6..8),
            Triple(JFK, SIN, 6..7),
            Triple(OVER_POLE_FROM, OVER_POLE_TO, 6..7),
        )
        for ((a, b, zooms) in cases) {
            val polygons = CorridorGeometry.strip(a, b)
            for (z in zooms) {
                val cover = TileCover.tiles(polygons, z)
                val reference = bruteForceCover(polygons, z)
                val missing = reference - cover
                assertTrue("$a-$b z$z misses $missing", missing.isEmpty())
                val extra = cover - reference
                assertTrue("$a-$b z$z: ${extra.size} extra tiles of ${reference.size}", extra.size * 100 <= reference.size)
            }
        }
    }

    @Test
    fun `every covered tile is near the route and every tile well inside is covered`() {
        for ((a, b) in listOf(LHR to JFK, SYD to LAX)) {
            val polygons = CorridorGeometry.strip(a, b)
            for (z in 6..9) {
                val cover = TileCover.tiles(polygons, z)
                for (tile in cover) {
                    val nearest = borderPoints(tile).minOf { distanceToSegment(a, b, it) }
                    // Or the route passes through the tile: then its border may be far, but the centre isn't.
                    val b0 = WebMercator.bounds(tile)
                    val centre = LatLon((b0.north + b0.south) / 2, (b0.west + b0.east) / 2)
                    assertTrue("$a-$b $tile is ${nearest / 1000} km away", nearest <= h + 3_000.0 || insideAny(polygons, centre))
                }
                for (tile in candidates(polygons, z)) {
                    val b0 = WebMercator.bounds(tile)
                    val centre = LatLon(WebMercator.latitude((WebMercator.y(b0.north) + WebMercator.y(b0.south)) / 2), (b0.west + b0.east) / 2)
                    if (distanceToSegment(a, b, centre) <= h - 2_000.0) assertTrue("$a-$b $tile not covered", tile in cover)
                }
            }
        }
    }

    @Test
    fun `LHR to JFK counts match an independent buffer count`() {
        // docs/research/scripts/ofm_corridor_sizes.py counts every tile with any part within
        // 175 km of the route by sampling tile borders: 33, 88, 279 and 967 tiles at zooms 6-9.
        val polygons = CorridorGeometry.strip(LHR, JFK)
        for ((z, expected) in listOf(6 to 33, 7 to 88, 8 to 279, 9 to 967)) {
            val count = TileCover.tiles(polygons, z).size
            assertTrue("z$z: $count tiles, expected about $expected", abs(count - expected) <= max(2, expected * 3 / 100))
        }
    }

    @Test
    fun `a corridor across the antimeridian covers tiles on both sides`() {
        val cover = TileCover.tiles(CorridorGeometry.strip(SYD, LAX), 6)
        assertTrue(cover.any { it.x == 0 })
        assertTrue(cover.any { it.x == 63 })
        // Nothing near the Greenwich meridian, half a world away.
        assertTrue(cover.none { it.x in 28..36 })
    }

    @Test
    fun `a corridor over the pole covers the top row of the map and stays small`() {
        val polygons = CorridorGeometry.strip(OVER_POLE_FROM, OVER_POLE_TO)
        val cover = TileCover.tiles(polygons, 6)
        assertEquals(64, cover.count { it.y == 0 })
        assertTrue(cover.size < 4096 / 10)
    }

    @Test
    fun `count adds up the zoom levels`() {
        val polygons = CorridorGeometry.strip(LHR, JFK)
        assertEquals((6..8).sumOf { TileCover.tiles(polygons, it).size }, TileCover.count(polygons, 6..8))
    }

    // --- helpers ---

    private fun ring(vararg points: LatLon) = points.toList() + points.first()

    /** Tile coordinates of a lon/lat point at zoom [z] (longitude 180 stays at the right edge). */
    private fun tileXY(p: LatLon, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        return (p.longitude + 180.0) / 360.0 * n to WebMercator.y(p.latitude) * n
    }

    /** All tiles inside each polygon's bounding box. */
    private fun candidates(polygons: List<List<LatLon>>, z: Int): Set<TileId> {
        val n = 1 shl z
        val out = HashSet<TileId>()
        for (ring in polygons) {
            val pts = ring.map { tileXY(it, z) }
            val x0 = floor(pts.minOf { it.first }).toInt().coerceIn(0, n - 1)
            val x1 = floor(pts.maxOf { it.first }).toInt().coerceIn(0, n - 1)
            val y0 = floor(pts.minOf { it.second }).toInt().coerceIn(0, n - 1)
            val y1 = floor(pts.maxOf { it.second }).toInt().coerceIn(0, n - 1)
            for (x in x0..x1) for (y in y0..y1) out += TileId(z, x, y)
        }
        return out
    }

    /**
     * The exact set of tiles whose closed square meets a polygon, tested tile by tile in tile
     * coordinates: a polygon vertex in the square, a square corner in the polygon, or an edge
     * crossing or touching a side of the square.
     */
    private fun bruteForceCover(polygons: List<List<LatLon>>, z: Int): Set<TileId> {
        val out = HashSet<TileId>()
        for (ring in polygons) {
            val pts = ring.map { tileXY(it, z) }
            for (tile in candidates(listOf(ring), z)) {
                val x0 = tile.x.toDouble()
                val y0 = tile.y.toDouble()
                val corners = listOf(x0 to y0, x0 + 1 to y0, x0 + 1 to y0 + 1, x0 to y0 + 1)
                val hit = pts.any { (x, y) -> x in x0..x0 + 1 && y in y0..y0 + 1 } ||
                    corners.any { insideRing(pts, it) } ||
                    (0 until pts.size - 1).any { i -> (0..3).any { k -> segmentsMeet(pts[i], pts[i + 1], corners[k], corners[(k + 1) % 4]) } }
                if (hit) out += tile
            }
        }
        return out
    }

    private fun insideRing(pts: List<Pair<Double, Double>>, p: Pair<Double, Double>): Boolean {
        var inside = false
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            if ((y0 > p.second) != (y1 > p.second) && x0 + (p.second - y0) / (y1 - y0) * (x1 - x0) > p.first) inside = !inside
        }
        return inside
    }

    private fun segmentsMeet(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>, s: Pair<Double, Double>): Boolean {
        fun orient(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double =
            (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        fun onSegment(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>) =
            c.first in min(a.first, b.first)..max(a.first, b.first) && c.second in min(a.second, b.second)..max(a.second, b.second)
        val d1 = orient(r, s, p)
        val d2 = orient(r, s, q)
        val d3 = orient(p, q, r)
        val d4 = orient(p, q, s)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        return (d1 == 0.0 && onSegment(r, s, p)) || (d2 == 0.0 && onSegment(r, s, q)) ||
            (d3 == 0.0 && onSegment(p, q, r)) || (d4 == 0.0 && onSegment(p, q, s))
    }

    /** Points along each side of [tile], corners included, at most about 5 km apart for zooms 6 and up. */
    private fun borderPoints(tile: TileId): List<LatLon> {
        val b = WebMercator.bounds(tile)
        val yN = WebMercator.y(b.north)
        val yS = WebMercator.y(b.south)
        val steps = max(16, 1 shl (13 - tile.z).coerceAtLeast(0))
        return (0..steps).flatMap { i ->
            val f = i.toDouble() / steps
            val lon = b.west + f * (b.east - b.west)
            val lat = WebMercator.latitude(yN + f * (yS - yN))
            listOf(LatLon(b.north, lon), LatLon(b.south, lon), LatLon(lat, b.west), LatLon(lat, b.east))
        }
    }
}
