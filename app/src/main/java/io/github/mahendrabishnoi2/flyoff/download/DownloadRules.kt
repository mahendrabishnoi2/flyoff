package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/** The kind of network the phone would download over. */
enum class NetworkKind {
    /** No network with internet. */
    NONE,

    /** Wi-Fi or Ethernet: not metered. */
    UNMETERED,

    /** Mobile data, or a Wi-Fi hotspot marked metered. */
    METERED,
}

/** Which networks a route map download may use: Wi-Fi only by default. */
object NetworkRule {

    /**
     * True when a [kind] download may run on [network]. Route maps may use mobile data when the
     * user allowed it ([mobileDataAllowed]); the detailed map is always Wi-Fi only.
     */
    fun allows(kind: DownloadKind, network: NetworkKind, mobileDataAllowed: Boolean): Boolean = when (network) {
        NetworkKind.NONE -> false
        NetworkKind.UNMETERED -> true
        NetworkKind.METERED -> kind == DownloadKind.ROUTE && mobileDataAllowed
    }
}

/**
 * When a tracked flight counts as landed, for deleting its maps ([CorridorRetention]). The app
 * has no schedule, only the tracked positions: the first one within [NEAR_DESTINATION_METERS]
 * of the destination (and nearer the destination than the origin, for very short routes) is the
 * landing time. A flight that was never tracked to its destination never "lands".
 */
object LandingRule {

    /** How near the destination a tracked position must be: about the final approach. */
    const val NEAR_DESTINATION_METERS: Double = 30_000.0

    fun isLanded(position: LatLon, origin: LatLon, destination: LatLon): Boolean {
        val toDestination = Geodesy.distanceMeters(position, destination)
        return toDestination <= NEAR_DESTINATION_METERS && toDestination < Geodesy.distanceMeters(position, origin)
    }
}

/** What MapLibre reports for one offline region (its `OfflineRegionStatus`), as plain numbers. */
data class RegionProgress(
    val completedTiles: Long,
    val completedResources: Long,
    /** Bytes of every completed resource (tiles and the style), raw: as MapLibre stores them. */
    val completedBytes: Long,
    val requiredResources: Long,
    val requiredPrecise: Boolean,
    val complete: Boolean,
) {
    /**
     * The tiles MapLibre will download in all, or null while it doesn't know yet: the required
     * resources minus the ones that are not tiles (the region's style).
     */
    val requiredTiles: Long?
        get() = if (requiredPrecise) requiredResources - (completedResources - completedTiles) else null

    companion object {
        val NONE = RegionProgress(0, 0, 0, 0, requiredPrecise = false, complete = false)
    }
}

/** Why a download was stopped by [DownloadGuard]. */
enum class GuardStop { TOO_MANY_TILES, TOO_MANY_BYTES }

/**
 * The hard limits of one download, summed over its regions, whatever the plan estimated:
 * OpenFreeMap allows [CorridorPlanner.MAX_TILES_PER_DOWNLOAD] tiles and
 * [CorridorPlanner.MAX_BYTES_PER_DOWNLOAD] bytes per download.
 */
object DownloadGuard {

    /**
     * Whether the download of [regions] (the finished ones and the running one) must stop: more
     * tiles completed or required than allowed, or more bytes completed. Null to go on.
     */
    fun check(regions: List<RegionProgress>): GuardStop? {
        val completedTiles = regions.sumOf { it.completedTiles }
        val requiredTiles = regions.sumOf { it.requiredTiles ?: it.completedTiles }
        val bytes = regions.sumOf { it.completedBytes }
        return when {
            completedTiles > CorridorPlanner.MAX_TILES_PER_DOWNLOAD || requiredTiles > CorridorPlanner.MAX_TILES_PER_DOWNLOAD -> GuardStop.TOO_MANY_TILES
            bytes > CorridorPlanner.MAX_BYTES_PER_DOWNLOAD -> GuardStop.TOO_MANY_BYTES
            else -> null
        }
    }

    /**
     * How far the download of [regions] is, from 0 to 1. [plannedTiles] are the planner's tile
     * counts of the same regions, used until MapLibre knows a region's exact count; a region not
     * started yet is [RegionProgress.NONE].
     */
    fun fraction(regions: List<RegionProgress>, plannedTiles: List<Int>): Double {
        require(regions.size == plannedTiles.size) { "${regions.size} regions, ${plannedTiles.size} planned counts" }
        var done = 0L
        var total = 0L
        for ((progress, planned) in regions.zip(plannedTiles)) {
            // The style is one resource more than the tiles.
            val expected = if (progress.requiredPrecise) progress.requiredResources else planned + 1L
            total += maxOf(expected, progress.completedResources)
            done += if (progress.complete) maxOf(expected, progress.completedResources) else progress.completedResources
        }
        return if (total == 0L) 0.0 else (done.toDouble() / total).coerceIn(0.0, 1.0)
    }
}
