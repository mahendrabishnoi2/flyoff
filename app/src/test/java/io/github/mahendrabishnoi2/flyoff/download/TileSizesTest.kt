package io.github.mahendrabishnoi2.flyoff.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSizesTest {

    /** Sample means of the land-heavy FRA–DEL corridor, wire bytes (docs/research, measured 2026-11-27). */
    private val fraDelMean = mapOf(6 to 133_200.0, 7 to 108_400.0, 8 to 93_900.0, 9 to 77_600.0)

    @Test
    fun `assumed sizes are the land-heavy mean with 50 percent headroom`() {
        for ((zoom, mean) in fraDelMean) {
            val assumed = TileSizes.bytesPerTile(zoom)
            assertTrue("z$zoom: $assumed", assumed >= mean * 1.5)
            assertTrue("z$zoom: $assumed", assumed <= mean * 1.6)
        }
    }

    @Test
    fun `assumed sizes per zoom`() {
        assertEquals(200_000L, TileSizes.bytesPerTile(6))
        assertEquals(165_000L, TileSizes.bytesPerTile(7))
        assertEquals(145_000L, TileSizes.bytesPerTile(8))
        assertEquals(120_000L, TileSizes.bytesPerTile(9))
    }

    @Test
    fun `estimate multiplies the tile count`() {
        assertEquals(0L, TileSizes.estimateBytes(8, 0))
        assertEquals(1_450_000L, TileSizes.estimateBytes(8, 10))
        assertEquals(279L * 145_000L, TileSizes.estimateBytes(8, 279))
    }

    @Test
    fun `unmeasured zooms are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TileSizes.bytesPerTile(5) }
        assertThrows(IllegalArgumentException::class.java) { TileSizes.bytesPerTile(10) }
        assertThrows(IllegalArgumentException::class.java) { TileSizes.estimateBytes(8, -1) }
    }
}
