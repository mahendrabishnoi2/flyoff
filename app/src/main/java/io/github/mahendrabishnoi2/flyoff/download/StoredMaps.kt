package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/** One offline region in MapLibre's database, as Flyoff reads it back. */
data class StoredRegion(
    /** MapLibre's region id. */
    val id: Long,
    val metadata: RegionMetadata,
    /** MapLibre has every resource of the region. */
    val complete: Boolean,
    /** Bytes on disk ([RegionProgress.completedBytes]). */
    val bytes: Long,
    /** The region's geometry: closed lon/lat rings, as it was created. */
    val polygons: List<List<LatLon>>,
) {
    /** The strip region of its chunk (the other one is the squares region). */
    val isStrip: Boolean get() = metadata.minZoom == metadata.kind.stripZoom
}

/**
 * The stored maps of one flight (one origin and destination), from what MapLibre has, so the
 * answers hold after an app update changes the planner or its size table.
 *
 * A chunk is complete when its strip region is there and every region of the chunk is complete.
 * Regions of a chunk are created squares first, strip last, so a chunk cut short between the two
 * never looks complete.
 */
data class FlightMaps(val origin: String, val destination: String, val regions: List<StoredRegion>) {

    /** Bytes on disk over all regions. */
    val bytes: Long get() = regions.sumOf { it.bytes }

    /** When the first of its downloads was made. */
    val createdAtMillis: Long get() = regions.minOf { it.metadata.createdAtMillis }

    /** When Flyoff saw the flight land, or null if it hasn't. */
    val landedAtMillis: Long? get() = regions.mapNotNull { it.metadata.landedAtMillis }.maxOrNull()

    /** The tile URL template every download of this flight must use (the first one's), or null if none. */
    val tileUrlTemplate: String? get() = regions.minByOrNull { it.metadata.createdAtMillis }?.metadata?.tileUrlTemplate

    fun regions(kind: DownloadKind): List<StoredRegion> = regions.filter { it.metadata.kind == kind }

    /**
     * How many chunks the stored [kind] downloads were planned with, or null when there are none
     * or they disagree (planned by different app versions).
     */
    fun chunkCount(kind: DownloadKind): Int? = regions(kind).map { it.metadata.chunkCount }.distinct().singleOrNull()

    /** True when stored [kind] downloads were planned with a different number of chunks than [plannedCount]. */
    fun conflictsWith(kind: DownloadKind, plannedCount: Int): Boolean =
        regions(kind).isNotEmpty() && chunkCount(kind) != plannedCount

    /** The indexes of the complete [kind] chunks. */
    fun completeChunks(kind: DownloadKind): Set<Int> {
        val count = chunkCount(kind) ?: return emptySet()
        return regions(kind).groupBy { it.metadata.chunkIndex }
            .filter { (index, chunk) -> index < count && chunk.any { it.isStrip } && chunk.all { it.complete } }
            .keys
    }

    /** Every [kind] chunk is downloaded. */
    fun isReady(kind: DownloadKind): Boolean {
        val count = chunkCount(kind) ?: return false
        return completeChunks(kind).size == count
    }

    /** True when some region of chunk [index] of [kind] is stored: a download to resume. */
    fun hasStarted(kind: DownloadKind, index: Int): Boolean = regions(kind).any { it.metadata.chunkIndex == index }

    /** The first chunk of a [plannedCount]-chunk [kind] plan still to download, or null when all are. */
    fun nextChunk(kind: DownloadKind, plannedCount: Int): Int? {
        val done = completeChunks(kind)
        return (0 until plannedCount).firstOrNull { it !in done }
    }

    /**
     * The map mask: the zoom-6 tiles of the complete route chunks, read from their squares
     * regions' geometry (see [CorridorGeometry.tileSquares]). Only tiles whose zoom-6 and zoom-7
     * levels were downloaded are in it.
     */
    val maskTiles: Set<TileId>
        get() {
            val complete = completeChunks(DownloadKind.ROUTE)
            return regions(DownloadKind.ROUTE)
                .filter { it.metadata.chunkIndex in complete && !it.isStrip }
                .flatMapTo(HashSet()) { TileCover.tiles(it.polygons, DownloadKind.ROUTE.squaresBaseZoom) }
        }

    /** The highest zoom of a complete download: 9 with a complete detailed chunk, else 8. */
    val maxZoom: Int
        get() = if (completeChunks(DownloadKind.DETAILED).isNotEmpty()) DownloadKind.DETAILED.stripZoom else DownloadKind.ROUTE.stripZoom

    /** True when [CorridorRetention] says this flight's maps are to be deleted at [nowMillis]. */
    fun isDueForDeletion(nowMillis: Long): Boolean = landedAtMillis?.let { CorridorRetention.isDueForDeletion(it, nowMillis) } ?: false

    companion object {
        /** [regions] grouped by flight, the most recently downloaded flight first. */
        fun byFlight(regions: List<StoredRegion>): List<FlightMaps> =
            regions.groupBy { it.metadata.origin to it.metadata.destination }
                .map { (codes, list) -> FlightMaps(codes.first, codes.second, list) }
                .sortedByDescending { flight -> flight.regions.maxOf { it.metadata.createdAtMillis } }
    }
}
