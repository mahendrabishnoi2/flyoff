package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/** What a route map download contains. */
enum class DownloadKind(
    /** All the zooms it downloads. */
    val zooms: IntRange,
    /** The zoom downloaded as the strip along the route. */
    val stripZoom: Int,
) {
    /**
     * The default corridor: every zoom-6 tile the strip touches, whole, with all its zoom-7
     * children, so the map has no patches with only zoom-6 detail; and zoom 8 along the strip.
     */
    ROUTE(6..8, stripZoom = 8),

    /** The optional "detailed" corridor: zoom 9 along the strip, added on top of [ROUTE]. */
    DETAILED(9..9, stripZoom = 9),
    ;

    /** The zooms downloaded as whole zoom-[squaresBaseZoom] tiles (empty for [DETAILED]). */
    val squaresZooms: IntRange get() = zooms.first until stripZoom

    /** The zoom of the tiles downloaded whole: the first of [squaresZooms]. */
    val squaresBaseZoom: Int get() = zooms.first
}

/** One MapLibre offline region: a geometry and the zooms to fetch inside it. */
data class DownloadRegion(
    /** The region's geometry: a multipolygon of closed lon/lat rings. */
    val polygons: List<List<LatLon>>,
    /** The region's min and max zoom. */
    val zooms: IntRange,
    /** Tiles over all [zooms], an upper bound of MapLibre's count for this region. */
    val tileCount: Int,
    /** Estimated raw (decompressed) bytes ([TileSizes]). */
    val estimatedBytes: Long,
)

/**
 * One user-started download of a plan: up to two MapLibre regions that together stay within
 * OpenFreeMap's per-download limits.
 *
 * It covers the route from [fromMeters] to [toMeters]; its strip reaches
 * [CorridorPlanner.CHUNK_OVERLAP_METERS] beyond both cut points so neighbouring chunks overlap.
 */
data class CorridorChunk(
    /** Position in the plan, from 0 at the origin. */
    val index: Int,
    /** Where this chunk's part of the route starts, in metres from the origin. */
    val fromMeters: Double,
    /** Where it ends, in metres from the origin. */
    val toMeters: Double,
    /**
     * The zoom-6 tiles this chunk downloads whole: those its strip touches that no earlier chunk
     * owns. The map mask is the union of these over the completed chunks. Empty for
     * [DownloadKind.DETAILED].
     */
    val ownedBaseTiles: Set<TileId>,
    /**
     * Region (a): [ownedBaseTiles] as inset squares, at [DownloadKind.squaresZooms] (6–7). Null
     * for [DownloadKind.DETAILED] or when every tile the strip touches is owned already.
     */
    val squaresRegion: DownloadRegion?,
    /** Region (b): the strip, at [DownloadKind.stripZoom] only. */
    val stripRegion: DownloadRegion,
) {
    /** The regions to create, squares first. */
    val regions: List<DownloadRegion> get() = listOfNotNull(squaresRegion, stripRegion)

    /** Tiles over both regions, the figure the per-download tile limit applies to. */
    val tileCount: Int get() = regions.sumOf { it.tileCount }

    /** Estimated raw bytes over both regions, the figure the per-download size limit applies to. */
    val estimatedBytes: Long get() = regions.sumOf { it.estimatedBytes }
}

/** A corridor split into downloads that each stay within OpenFreeMap's limits. */
data class CorridorPlan(
    val kind: DownloadKind,
    /** The downloads in route order; at least one. */
    val chunks: List<CorridorChunk>,
    /** Distinct tiles over all chunks (tiles in the strip overlaps counted once). */
    val totalTiles: Int,
    /** Estimated raw bytes of [totalTiles]. */
    val totalEstimatedBytes: Long,
) {
    /** Every zoom-6 tile downloaded whole, over all chunks (empty for [DownloadKind.DETAILED]). */
    val baseTiles: Set<TileId> get() = chunks.flatMapTo(HashSet()) { it.ownedBaseTiles }
}

/**
 * Splits a flight's corridor into downloads ("chunks") that each respect OpenFreeMap's terms:
 * at most [MAX_TILES_PER_DOWNLOAD] tiles and [MAX_BYTES_PER_DOWNLOAD] bytes per download, over
 * both of a chunk's regions. Each chunk aims for the lower [TARGET_TILES_PER_CHUNK] and
 * [TARGET_BYTES_PER_CHUNK], a margin for estimates that run low.
 *
 * Chunks are consecutive pieces of the route, each as long as the targets allow (found by
 * bisection to within [SEARCH_PRECISION_METERS]), starting from the origin. A zoom-6 tile that
 * two chunks touch belongs to the first. A route that fits in one download is one chunk.
 */
object CorridorPlanner {

    /** OpenFreeMap's limit on tiles per download. */
    const val MAX_TILES_PER_DOWNLOAD: Int = 3_000

    /**
     * OpenFreeMap's limit on bytes per download (50 MB, read as 50,000,000 bytes), counted as
     * raw (decompressed) tile bytes, as MapLibre reports them.
     */
    const val MAX_BYTES_PER_DOWNLOAD: Long = 50_000_000L

    /** The most tiles the planner puts in one chunk. */
    const val TARGET_TILES_PER_CHUNK: Int = 2_700

    /** The most estimated raw bytes the planner puts in one chunk. */
    const val TARGET_BYTES_PER_CHUNK: Long = 40_000_000L

    /** How far each chunk's strip reaches past its cut points into its neighbours. */
    const val CHUNK_OVERLAP_METERS: Double = 5_000.0

    /** The shortest chunk, and how close the bisection gets to the longest chunk that fits. */
    const val SEARCH_PRECISION_METERS: Double = 10_000.0

    /**
     * The downloads for the [kind] corridor of [halfWidthMeters] along the great circle from
     * [origin] to [destination], sized with [sizes]. Throws [IllegalStateException] if even a
     * [SEARCH_PRECISION_METERS] piece of the corridor would break a target, which does not
     * happen for the default width.
     */
    fun plan(
        origin: LatLon,
        destination: LatLon,
        kind: DownloadKind,
        sizes: TileSizes,
        halfWidthMeters: Double = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS,
    ): CorridorPlan {
        val length = Geodesy.distanceMeters(origin, destination)
        val chunks = ArrayList<CorridorChunk>()
        val owned = HashSet<TileId>()
        var start = 0.0
        fun build(end: Double) = chunk(origin, destination, kind, sizes, start, end, chunks.size, owned, halfWidthMeters)
        while (true) {
            val rest = build(length)
            if (fits(rest)) {
                chunks += rest
                break
            }
            var low = minOf(start + SEARCH_PRECISION_METERS, length)
            var best = build(low)
            check(fits(best)) { "a ${SEARCH_PRECISION_METERS / 1000} km piece of the corridor exceeds the per-download targets" }
            var high = length
            while (high - low > SEARCH_PRECISION_METERS) {
                val mid = (low + high) / 2
                val candidate = build(mid)
                if (fits(candidate)) {
                    low = mid
                    best = candidate
                } else {
                    high = mid
                }
            }
            chunks += best
            owned += best.ownedBaseTiles
            start = low
        }

        // Squares are disjoint between chunks; strips overlap, so count their tiles once.
        val distinct = HashSet<TileId>()
        for (chunk in chunks) {
            distinct += squaresTiles(chunk.ownedBaseTiles, kind)
            distinct += TileCover.tiles(chunk.stripRegion.polygons, kind.stripZoom)
        }
        return CorridorPlan(kind, chunks, distinct.size, sizes.rawBytes(distinct))
    }

    /**
     * The chunk numbered [index] that covers the route from [fromMeters] to [toMeters], with its
     * strip reaching [CHUNK_OVERLAP_METERS] further at both cut points, owning the zoom-6 tiles
     * its strip touches except those in [ownedBefore], and with its tiles counted and sized.
     * When [origin] and [destination] are the same airport the strip is the circle around it.
     */
    fun chunk(
        origin: LatLon,
        destination: LatLon,
        kind: DownloadKind,
        sizes: TileSizes,
        fromMeters: Double,
        toMeters: Double,
        index: Int,
        ownedBefore: Set<TileId> = emptySet(),
        halfWidthMeters: Double = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS,
    ): CorridorChunk {
        val length = Geodesy.distanceMeters(origin, destination)
        val strip = CorridorGeometry.strip(
            origin,
            destination,
            halfWidthMeters,
            fromMeters = (fromMeters - CHUNK_OVERLAP_METERS).coerceAtLeast(0.0),
            toMeters = (toMeters + CHUNK_OVERLAP_METERS).coerceAtMost(length),
        )
        val stripTiles = TileCover.tiles(strip, kind.stripZoom)
        val stripRegion = DownloadRegion(strip, kind.stripZoom..kind.stripZoom, stripTiles.size, sizes.rawBytes(stripTiles))

        if (kind.squaresZooms.isEmpty()) return CorridorChunk(index, fromMeters, toMeters, emptySet(), null, stripRegion)
        val owned = TileCover.tiles(strip, kind.squaresBaseZoom) - ownedBefore
        val squaresRegion = if (owned.isEmpty()) {
            null
        } else {
            val tiles = squaresTiles(owned, kind)
            DownloadRegion(CorridorGeometry.tileSquares(owned), kind.squaresZooms, tiles.size, sizes.rawBytes(tiles))
        }
        return CorridorChunk(index, fromMeters, toMeters, owned, squaresRegion, stripRegion)
    }

    /** [base] and all their descendants down to the last of [DownloadKind.squaresZooms]. */
    private fun squaresTiles(base: Set<TileId>, kind: DownloadKind): Set<TileId> {
        val out = HashSet<TileId>()
        var level: Set<TileId> = base
        for (zoom in kind.squaresZooms) {
            out += level
            if (zoom < kind.squaresZooms.last) {
                level = level.flatMapTo(HashSet()) { t ->
                    listOf(TileId(zoom + 1, 2 * t.x, 2 * t.y), TileId(zoom + 1, 2 * t.x + 1, 2 * t.y), TileId(zoom + 1, 2 * t.x, 2 * t.y + 1), TileId(zoom + 1, 2 * t.x + 1, 2 * t.y + 1))
                }
            }
        }
        return out
    }

    private fun fits(chunk: CorridorChunk): Boolean =
        chunk.tileCount <= TARGET_TILES_PER_CHUNK && chunk.estimatedBytes <= TARGET_BYTES_PER_CHUNK
}
