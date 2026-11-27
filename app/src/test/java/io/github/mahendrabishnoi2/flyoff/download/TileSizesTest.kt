package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.DEL
import io.github.mahendrabishnoi2.flyoff.download.Routes.FRA
import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LAX
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.download.Routes.SYD
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.ceil

/** The bundled table, read from the source tree as the app will read it from its assets. */
val bundledTileSizes: ByteArray by lazy { File("src/main/assets/basemap/tile_sizes.bin").readBytes() }

class TileSizesTest {

    /** A table with base zoom 1 (4 areas) and zooms 6..7, each cell's mean = 64 × (1 + index), max = 2 × mean. */
    private fun table(magic: String = "FTSZ", version: Int = 1, compression: Int = 2, extra: Int = 0, cut: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes(magic)
            writeByte(version)
            writeByte(1) // base zoom
            writeByte(6)
            writeByte(7)
            writeByte(compression)
            writeShort(64)
            writeByte(4)
            writeBytes("test")
            for (i in 0 until 2 * 4) {
                writeShort(1 + i)
                writeShort(2 * (1 + i))
            }
            repeat(extra) { writeByte(0) }
        }
        val bytes = out.toByteArray()
        return bytes.copyOf(bytes.size - cut)
    }

    @Test
    fun `a table maps each tile to its base-zoom area`() {
        val t = TileSizeTable.fromBytes(table())!!
        assertEquals("test", t.build)
        assertEquals(6..7, t.zooms)
        // Zoom 6 tile (40, 10) is in base-zoom-1 area (1, 0): index 1.
        assertEquals(64L * 2, t.storedMeanBytes(TileId(6, 40, 10)))
        assertEquals(64L * 4, t.storedMaxBytes(TileId(6, 40, 10)))
        // Zoom 7 tile (10, 100) is in area (0, 1): index 4 + 2.
        assertEquals(64L * 7, t.storedMeanBytes(TileId(7, 10, 100)))
        assertThrows(IllegalArgumentException::class.java) { t.storedMeanBytes(TileId(8, 0, 0)) }
    }

    @Test
    fun `damaged tables are refused`() {
        assertNull(TileSizeTable.fromBytes(ByteArray(0)))
        assertNull(TileSizeTable.fromBytes(table(magic = "PMTi")))
        assertNull(TileSizeTable.fromBytes(table(version = 2)))
        assertNull(TileSizeTable.fromBytes(table(extra = 1)))
        assertNull(TileSizeTable.fromBytes(table(cut = 1)))
        assertNull(TileSizeTable.fromBytes(table(cut = 30)))
    }

    @Test
    fun `missing, damaged or non-gzip tables fall back to flat estimates`() {
        assertSame(TileSizes.FLAT, TileSizes.fromBytes(null))
        assertSame(TileSizes.FLAT, TileSizes.fromBytes(byteArrayOf(1, 2, 3)))
        assertSame(TileSizes.FLAT, TileSizes.fromBytes(table(compression = 1)))
        assertFalse(TileSizes.FLAT.usesTable)
        assertEquals(215_000L, TileSizes.FLAT.rawBytes(TileId(8, 127, 85)))
    }

    @Test
    fun `flat estimates are the land-heavy raw mean with 50 percent headroom`() {
        // FRA–DEL raw sample means, docs/research (measured 2026-11-27).
        val fraDelRawMean = mapOf(6 to 236_600.0, 7 to 168_800.0, 8 to 141_200.0, 9 to 117_400.0)
        for ((zoom, mean) in fraDelRawMean) {
            val flat = TileSizes.flatRawBytesPerTile(zoom)
            assertTrue("z$zoom: $flat", flat >= mean * 1.5 && flat <= mean * 1.55)
        }
        assertThrows(IllegalArgumentException::class.java) { TileSizes.flatRawBytesPerTile(5) }
        assertThrows(IllegalArgumentException::class.java) { TileSizes.FLAT.rawBytes(TileId(10, 0, 0)) }
    }

    @Test
    fun `a table estimate is the area mean times the raw ratio and the safety factor, with a floor`() {
        val sizes = TileSizes.fromBytes(table())
        assertTrue(sizes.usesTable)
        // Area index 7 at zoom 7: mean 8 × 64 = 512 stored bytes.
        val tile = TileId(7, 127, 127)
        assertEquals(ceil(512 * 1.6 * 1.5).toLong(), sizes.rawBytes(tile))
        // Area index 0 at zoom 6: 64 stored bytes, × 1.8 × 1.5 = 173, raised to the floor.
        assertEquals(TileSizes.MIN_TILE_BYTES, sizes.rawBytes(TileId(6, 0, 0)))
        // Zooms the table lacks use the flat figure.
        assertEquals(TileSizes.flatRawBytesPerTile(8), sizes.rawBytes(TileId(8, 0, 0)))
        assertEquals(sizes.rawBytes(tile) * 2, sizes.rawBytes(listOf(tile, tile)))
    }

    @Test
    fun `the bundled table loads`() {
        val table = TileSizeTable.fromBytes(bundledTileSizes)!!
        assertEquals("20261101_080013_pt", table.build)
        assertEquals(6..9, table.zooms)
        assertTrue(TileSizes.fromBytes(bundledTileSizes).usesTable)
        // Land costs more than open sea.
        val sizes = TileSizes.fromBytes(bundledTileSizes)
        val london = sizes.rawBytes(WebMercator.tileAt(LHR, 8))
        val pacific = sizes.rawBytes(WebMercator.tileAt(LatLon(5.0, -140.0), 8))
        assertTrue("$london vs $pacific", london > 20 * pacific)
    }

    @Test
    fun `estimates cover the measured corridors without wild overestimates`() {
        // Measured raw totals (sampled tile sizes × tile count; strip at every zoom), MB.
        val measured = listOf(
            Triple(LHR to JFK, 23.9, 39.5),
            Triple(FRA to DEL, 57.5, 101.3),
            Triple(SYD to LAX, 5.8, 4.2),
        )
        val sizes = TileSizes.fromBytes(bundledTileSizes)
        for ((route, routeMb, detailedMb) in measured) {
            val strip = CorridorGeometry.strip(route.first, route.second)
            val z68 = (6..8).sumOf { z -> sizes.rawBytes(TileCover.tiles(strip, z)) } / 1e6
            val z9 = sizes.rawBytes(TileCover.tiles(strip, 9)) / 1e6
            assertTrue("$route z6-8: $z68 MB < $routeMb", z68 >= routeMb)
            assertTrue("$route z9: $z9 MB < $detailedMb", z9 >= detailedMb)
            assertTrue("$route z6-8: $z68 MB", z68 <= 2.5 * routeMb)
            assertTrue("$route z9: $z9 MB", z9 <= 2.5 * detailedMb)
        }
    }
}
