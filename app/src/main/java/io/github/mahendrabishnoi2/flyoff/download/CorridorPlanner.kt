package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/** What a route map download contains. */
enum class DownloadKind(
    /** The zooms it downloads. */
    val zooms: IntRange,
) {
    /** The default corridor: zooms 6 to 8 (the bundled world map stops at 5). */
    ROUTE(6..8),

    /** The optional "detailed" corridor: zoom 9 only, added on top of [ROUTE]. */
    DETAILED(9..9),
}

/**
 * One download of a plan: a MapLibre offline region of its own.
 *
 * It covers the route from [fromMeters] to [toMeters] along it, and its [polygons] reach
 * [CorridorPlanner.CHUNK_OVERLAP_METERS] beyond both cut points so neighbouring chunks overlap.
 */
data class CorridorChunk(
    /** Position in the plan, from 0 at the origin. */
    val index: Int,
    /** Where this chunk's part of the route starts, in metres from the origin. */
    val fromMeters: Double,
    /** Where it ends, in metres from the origin. */
    val toMeters: Double,
    /** The region's geometry: a multipolygon in [CorridorGeometry.strip]'s format. */
    val polygons: List<List<LatLon>>,
    /** The zooms to download (the region's min and max zoom). */
    val zooms: IntRange,
    /** Tiles over all [zooms], an upper bound of MapLibre's count for this region. */
    val tileCount: Int,
    /** Estimated download size in raw (decompressed) bytes ([TileSizes]). */
    val estimatedBytes: Long,
)

/** A corridor split into downloads that each stay within OpenFreeMap's limits. */
data class CorridorPlan(
    val kind: DownloadKind,
    /** The downloads in route order; at least one. */
    val chunks: List<CorridorChunk>,
    /** Distinct tiles over all chunks (tiles in the overlaps counted once). */
    val totalTiles: Int,
    /** Estimated bytes of [totalTiles]. */
    val totalEstimatedBytes: Long,
)

/**
 * Splits a flight's corridor into downloads ("chunks") that each respect OpenFreeMap's terms:
 * at most [MAX_TILES_PER_DOWNLOAD] tiles and [MAX_BYTES_PER_DOWNLOAD] bytes per download. Each
 * chunk aims for the lower [TARGET_TILES_PER_CHUNK] and [TARGET_BYTES_PER_CHUNK], a margin for
 * tile-size estimates that run low.
 *
 * Chunks are consecutive pieces of the route, each as long as the targets allow (found by
 * bisection to within [SEARCH_PRECISION_METERS]), starting from the origin. A route that fits in
 * one download is one chunk.
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

    /** The most estimated bytes the planner puts in one chunk. */
    const val TARGET_BYTES_PER_CHUNK: Long = 40_000_000L

    /** How far each chunk's strip reaches past its cut points into its neighbours. */
    const val CHUNK_OVERLAP_METERS: Double = 5_000.0

    /** The shortest chunk, and how close the bisection gets to the longest chunk that fits. */
    const val SEARCH_PRECISION_METERS: Double = 10_000.0

    /**
     * The downloads for the [kind] corridor of [halfWidthMeters] along the great circle from
     * [origin] to [destination]. Throws [IllegalStateException] if even a
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
        var start = 0.0
        while (true) {
            val rest = chunk(origin, destination, kind, sizes, start, length, chunks.size, halfWidthMeters)
            if (fits(rest)) {
                chunks += rest
                break
            }
            var low = minOf(start + SEARCH_PRECISION_METERS, length)
            var best = chunk(origin, destination, kind, sizes, start, low, chunks.size, halfWidthMeters)
            check(fits(best)) { "a ${SEARCH_PRECISION_METERS / 1000} km piece of the corridor exceeds the per-download targets" }
            var high = length
            while (high - low > SEARCH_PRECISION_METERS) {
                val mid = (low + high) / 2
                val candidate = chunk(origin, destination, kind, sizes, start, mid, chunks.size, halfWidthMeters)
                if (fits(candidate)) {
                    low = mid
                    best = candidate
                } else {
                    high = mid
                }
            }
            chunks += best
            start = low
        }

        var totalTiles = 0
        var totalBytes = 0L
        for (zoom in kind.zooms) {
            val distinct = chunks.flatMapTo(HashSet()) { TileCover.tiles(it.polygons, zoom) }
            totalTiles += distinct.size
            totalBytes += sizes.rawBytes(distinct)
        }
        return CorridorPlan(kind, chunks, totalTiles, totalBytes)
    }

    /**
     * The chunk numbered [index] that covers the route from [fromMeters] to [toMeters], with its
     * strip reaching [CHUNK_OVERLAP_METERS] further at both cut points and its tiles counted.
     * When [origin] and [destination] are the same airport the chunk is the circle around it.
     */
    fun chunk(
        origin: LatLon,
        destination: LatLon,
        kind: DownloadKind,
        sizes: TileSizes,
        fromMeters: Double,
        toMeters: Double,
        index: Int,
        halfWidthMeters: Double = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS,
    ): CorridorChunk {
        val length = Geodesy.distanceMeters(origin, destination)
        val polygons = CorridorGeometry.strip(
            origin,
            destination,
            halfWidthMeters,
            fromMeters = (fromMeters - CHUNK_OVERLAP_METERS).coerceAtLeast(0.0),
            toMeters = (toMeters + CHUNK_OVERLAP_METERS).coerceAtMost(length),
        )
        var tiles = 0
        var bytes = 0L
        for (zoom in kind.zooms) {
            val covered = TileCover.tiles(polygons, zoom)
            tiles += covered.size
            bytes += sizes.rawBytes(covered)
        }
        return CorridorChunk(index, fromMeters, toMeters, polygons, kind.zooms, tiles, bytes)
    }

    private fun fits(chunk: CorridorChunk): Boolean =
        chunk.tileCount <= TARGET_TILES_PER_CHUNK && chunk.estimatedBytes <= TARGET_BYTES_PER_CHUNK
}
