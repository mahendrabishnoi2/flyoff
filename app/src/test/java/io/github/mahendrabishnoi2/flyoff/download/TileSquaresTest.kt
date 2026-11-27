package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LAX
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SYD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSquaresTest {

    private fun children(tiles: Set<TileId>): Set<TileId> =
        tiles.flatMapTo(HashSet()) { t -> listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) -> TileId(t.z + 1, 2 * t.x + dx, 2 * t.y + dy) } }

    @Test
    fun `inset squares cover exactly their tiles and children, never a neighbour`() {
        val sets = listOf(
            TileCover.tiles(CorridorGeometry.strip(LHR, JFK), 6),
            TileCover.tiles(CorridorGeometry.strip(SYD, LAX), 6), // both sides of the antimeridian
            TileCover.tiles(CorridorGeometry.strip(OVER_POLE_FROM, OVER_POLE_TO), 6), // the whole top row
            setOf(TileId(6, 0, 63), TileId(6, 63, 63), TileId(6, 20, 20), TileId(6, 22, 20), TileId(6, 21, 21)), // corners, checkerboard
        )
        for (tiles in sets) {
            val squares = CorridorGeometry.tileSquares(tiles)
            assertEquals(tiles, TileCover.tiles(squares, 6))
            assertEquals(children(tiles), TileCover.tiles(squares, 7))
        }
    }

    @Test
    fun `adjacent tiles in a row merge into one rectangle`() {
        val squares = CorridorGeometry.tileSquares(setOf(TileId(6, 10, 20), TileId(6, 11, 20), TileId(6, 13, 20), TileId(6, 10, 21)))
        assertEquals(3, squares.size)
        for (ring in squares) {
            assertEquals(5, ring.size)
            assertEquals(ring.first(), ring.last())
            assertTrue(signedArea(ring) > 0)
        }
        val wide = squares.maxBy { r -> r.maxOf { it.longitude } - r.minOf { it.longitude } }
        val b10 = WebMercator.bounds(TileId(6, 10, 20))
        val b11 = WebMercator.bounds(TileId(6, 11, 20))
        assertEquals(b10.west, wide.minOf { it.longitude }, 0.1)
        assertEquals(b11.east, wide.maxOf { it.longitude }, 0.1)
    }

    @Test
    fun `squares stay inside the map`() {
        val squares = CorridorGeometry.tileSquares(setOf(TileId(6, 0, 0), TileId(6, 63, 0), TileId(6, 63, 63)))
        for (ring in squares) {
            assertTrue(ring.all { it.longitude > -180.0 && it.longitude < 180.0 })
            assertTrue(ring.all { it.latitude > -WebMercator.MAX_LATITUDE && it.latitude < WebMercator.MAX_LATITUDE })
        }
    }

    @Test
    fun `no tiles give no squares, mixed zooms are refused`() {
        assertTrue(CorridorGeometry.tileSquares(emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.tileSquares(setOf(TileId(6, 0, 0), TileId(7, 0, 0))) }
    }
}
