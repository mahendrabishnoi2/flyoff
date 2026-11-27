package io.github.mahendrabishnoi2.flyoff.download

/**
 * How many bytes an OpenFreeMap vector tile is assumed to take, for planning downloads.
 *
 * The figures are bytes on the wire (gzip, as the server sends them), measured on 2026-11-27
 * from a sample of tiles along three corridors (`docs/research/scripts/ofm_corridor_sizes.py`,
 * results in `docs/research/data-sources-validation.md` §5.2). They are the land-heavy FRA–DEL
 * corridor's mean per zoom × 1.5, rounded up: a download chunk can lie entirely over land, so
 * the mean of a mixed route would undercount it, and the headroom covers denser land (central
 * Europe, the eastern US) and the sampling error. They overstate ocean routes several times over
 * (SYD–LAX averages 3–16 KB a tile), so a size built from them is an upper estimate.
 */
object TileSizes {

    /** The zooms the figures were measured for. */
    val MEASURED_ZOOMS: IntRange = 6..9

    /** Assumed bytes per tile at [zoom], which must be in [MEASURED_ZOOMS]. */
    fun bytesPerTile(zoom: Int): Long = when (zoom) {
        6 -> 200_000L
        7 -> 165_000L
        8 -> 145_000L
        9 -> 120_000L
        else -> throw IllegalArgumentException("no tile size measured for zoom $zoom (only $MEASURED_ZOOMS)")
    }

    /** Estimated bytes for [tiles] tiles at [zoom]. */
    fun estimateBytes(zoom: Int, tiles: Int): Long {
        require(tiles >= 0) { "tile count $tiles is negative" }
        return bytesPerTile(zoom) * tiles
    }
}
