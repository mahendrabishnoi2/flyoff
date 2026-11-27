package io.github.mahendrabishnoi2.flyoff.download

import kotlin.math.ceil
import kotlin.math.max

/**
 * Estimates how many raw (decompressed) bytes OpenFreeMap tiles take, for planning downloads.
 *
 * Raw bytes are what MapLibre counts (OkHttp un-gzips tiles before MapLibre sees them), so the
 * planner and the download's runtime size guard agree.
 *
 * With a [TileSizeTable], a tile is estimated as its zoom-5 area's mean stored size at that zoom
 * × [rawRatio] × [SAFETY_FACTOR], and at least [MIN_TILE_BYTES]. Without one ([FLAT]) every tile
 * at a zoom gets the same conservative figure ([flatRawBytesPerTile]).
 *
 * Calibration (2026-11-27, `docs/research/data-sources-validation.md` §5.2): the server sends
 * exactly the stored bytes; raw/stored ratios of the measured corridors were at most 1.78, 1.56,
 * 1.51 and 1.52 at zooms 6-9. A corridor runs between cities, so its tiles are denser than its
 * areas' means: with a factor of 1.0 or 1.25 LHR–JFK and FRA–DEL came out under their measured
 * totals, with 1.5 none did, while using the areas' maximum overstated zoom 9 by 2-14×.
 */
class TileSizes private constructor(private val table: TileSizeTable?) {

    /** True when estimates come from a size table, false for the flat fallback. */
    val usesTable: Boolean get() = table != null

    /** Estimated raw bytes of [tile], whose zoom must be in 6..9. */
    fun rawBytes(tile: TileId): Long {
        val flat = flatRawBytesPerTile(tile.z)
        val t = table ?: return flat
        if (tile.z !in t.zooms) return flat
        return max(MIN_TILE_BYTES, ceil(t.storedMeanBytes(tile) * rawRatio(tile.z) * SAFETY_FACTOR).toLong())
    }

    /** Estimated raw bytes of all [tiles]. */
    fun rawBytes(tiles: Iterable<TileId>): Long = tiles.sumOf { rawBytes(it) }

    companion object {
        /** Headroom over an area's mean tile size. */
        const val SAFETY_FACTOR: Double = 1.5

        /** The least a tile is estimated at (a sea tile is about 80 bytes stored). */
        const val MIN_TILE_BYTES: Long = 512L

        /** Estimates without a table: every tile at the flat figure. */
        val FLAT: TileSizes = TileSizes(null)

        /**
         * Estimates from the size table in [bytes] (`assets/basemap/tile_sizes.bin`), or [FLAT] if
         * [bytes] is null, not a valid table, or not of gzip tiles.
         */
        fun fromBytes(bytes: ByteArray?): TileSizes {
            val table = bytes?.let(TileSizeTable::fromBytes) ?: return FLAT
            return if (table.tileCompression == GZIP) TileSizes(table) else FLAT
        }

        /** Raw bytes per stored (gzip) byte at [zoom] (6..9): the measured maximum, rounded up. */
        fun rawRatio(zoom: Int): Double = when (zoom) {
            6 -> 1.8
            7 -> 1.6
            8, 9 -> 1.55
            else -> throw IllegalArgumentException("no ratio for zoom $zoom (only 6..9)")
        }

        /**
         * The fallback estimate per tile at [zoom] (6..9): the land-heavy FRA–DEL corridor's
         * measured raw mean × 1.5, rounded up, so a chunk entirely over land stays within the limit.
         */
        fun flatRawBytesPerTile(zoom: Int): Long = when (zoom) {
            6 -> 360_000L
            7 -> 255_000L
            8 -> 215_000L
            9 -> 180_000L
            else -> throw IllegalArgumentException("no tile size for zoom $zoom (only 6..9)")
        }

        private const val GZIP = 2
    }
}
