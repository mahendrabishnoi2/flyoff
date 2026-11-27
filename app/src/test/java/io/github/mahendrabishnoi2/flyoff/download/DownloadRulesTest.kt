package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.CDG
import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRulesTest {

    // ---- Network ----

    @Test
    fun `route maps download on Wi-Fi, or on mobile data when allowed`() {
        for (allowed in listOf(false, true)) {
            assertTrue(NetworkRule.allows(DownloadKind.ROUTE, NetworkKind.UNMETERED, allowed))
            assertFalse(NetworkRule.allows(DownloadKind.ROUTE, NetworkKind.NONE, allowed))
        }
        assertFalse(NetworkRule.allows(DownloadKind.ROUTE, NetworkKind.METERED, mobileDataAllowed = false))
        assertTrue(NetworkRule.allows(DownloadKind.ROUTE, NetworkKind.METERED, mobileDataAllowed = true))
    }

    @Test
    fun `the detailed map is always Wi-Fi only`() {
        assertTrue(NetworkRule.allows(DownloadKind.DETAILED, NetworkKind.UNMETERED, mobileDataAllowed = false))
        assertFalse(NetworkRule.allows(DownloadKind.DETAILED, NetworkKind.METERED, mobileDataAllowed = true))
        assertFalse(NetworkRule.allows(DownloadKind.DETAILED, NetworkKind.NONE, mobileDataAllowed = true))
    }

    // ---- Landing ----

    @Test
    fun `a flight lands when a tracked position is within 30 km of the destination`() {
        assertTrue(LandingRule.isLanded(JFK, LHR, JFK))
        // About 25 km and 40 km east of JFK.
        assertTrue(LandingRule.isLanded(LatLon(40.6394, -73.4840), LHR, JFK))
        assertFalse(LandingRule.isLanded(LatLon(40.6394, -73.3060), LHR, JFK))
        assertFalse(LandingRule.isLanded(LHR, LHR, JFK))
        assertFalse(LandingRule.isLanded(LatLon(51.0, -30.0), LHR, JFK))
    }

    @Test
    fun `on a very short route the departure doesn't count as a landing`() {
        val origin = LatLon(51.0, 0.0)
        val destination = LatLon(51.0, 0.3) // About 21 km apart.
        assertFalse(LandingRule.isLanded(origin, origin, destination))
        assertTrue(LandingRule.isLanded(destination, origin, destination))
    }

    // ---- Guards ----

    private fun progress(tiles: Long, bytes: Long = tiles * 10_000, required: Long? = null, complete: Boolean = false) = RegionProgress(
        completedTiles = tiles,
        completedResources = tiles + 1,
        completedBytes = bytes,
        requiredResources = required ?: (tiles + 1),
        requiredPrecise = required != null,
        complete = complete,
    )

    @Test
    fun `required tiles leave out the style`() {
        assertEquals(400L, progress(10, required = 401).requiredTiles)
        assertNull(progress(10).requiredTiles)
        assertNull(RegionProgress.NONE.requiredTiles)
    }

    @Test
    fun `a download within the limits goes on`() {
        assertNull(DownloadGuard.check(listOf(progress(2_000, required = 2_001, complete = true), progress(500, required = 1_001))))
        assertNull(DownloadGuard.check(listOf(progress(3_000, bytes = 50_000_000, required = 3_001, complete = true))))
        assertNull(DownloadGuard.check(listOf(RegionProgress.NONE)))
    }

    @Test
    fun `a download stops before it needs more than 3,000 tiles over its regions`() {
        // MapLibre's exact count is known before any tile arrives.
        assertEquals(GuardStop.TOO_MANY_TILES, DownloadGuard.check(listOf(progress(0, required = 3_002))))
        assertEquals(GuardStop.TOO_MANY_TILES, DownloadGuard.check(listOf(progress(2_000, complete = true, required = 2_001), progress(0, required = 1_002))))
        assertEquals(GuardStop.TOO_MANY_TILES, DownloadGuard.check(listOf(progress(3_001))))
    }

    @Test
    fun `a download stops past 50 MB over its regions`() {
        assertEquals(GuardStop.TOO_MANY_BYTES, DownloadGuard.check(listOf(progress(100, bytes = 30_000_000, complete = true), progress(100, bytes = 20_000_001))))
    }

    @Test
    fun `progress counts every region of the download`() {
        val planned = listOf(165, 279)
        assertEquals(0.0, DownloadGuard.fraction(listOf(RegionProgress.NONE, RegionProgress.NONE), planned), 0.0)
        // Squares done (166 resources), strip not started: 166 of 166 + 280.
        val squaresDone = progress(165, required = 166, complete = true)
        assertEquals(166.0 / 446, DownloadGuard.fraction(listOf(squaresDone, RegionProgress.NONE), planned), 1e-9)
        // MapLibre's exact count replaces the plan's.
        assertEquals(166.0 / 366, DownloadGuard.fraction(listOf(squaresDone, progress(0, required = 200).copy(completedResources = 0)), planned), 1e-9)
        assertEquals(1.0, DownloadGuard.fraction(listOf(squaresDone, progress(250, required = 251, complete = true)), planned), 0.0)
    }
}

class CorridorMaskFromStorageTest {

    private val template = "https://tiles.openfreemap.org/planet/20261122_124456_pt/{z}/{x}/{y}.pbf"
    private val sizes = TileSizes.fromBytes(bundledTileSizes)

    private fun metadata(kind: DownloadKind, index: Int, count: Int, zooms: IntRange, landed: Long? = null, created: Long = 1_000L) =
        RegionMetadata("LHR", "JFK", kind, index, count, zooms.first, zooms.last, created, landed, template)

    /** The regions MapLibre would hold for [plan]'s chunks [done] (complete) and [partial] (strip incomplete). */
    private fun stored(plan: CorridorPlan, done: Set<Int>, partial: Set<Int> = emptySet()): List<StoredRegion> {
        var id = 0L
        return plan.chunks.filter { it.index in done || it.index in partial }.flatMap { chunk ->
            chunk.regions.map { region ->
                val isStrip = region === chunk.stripRegion
                StoredRegion(
                    id = id++,
                    metadata = metadata(plan.kind, chunk.index, plan.chunks.size, region.zooms),
                    complete = chunk.index in done || !isStrip,
                    bytes = region.estimatedBytes,
                    polygons = region.polygons,
                )
            }
        }
    }

    @Test
    fun `the mask is read back from the stored squares regions`() {
        val plan = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes)
        val maps = FlightMaps("LHR", "JFK", stored(plan, done = plan.chunks.map { it.index }.toSet()))
        assertEquals(plan.baseTiles, maps.maskTiles)
        assertTrue(maps.isReady(DownloadKind.ROUTE))
        assertEquals(8, maps.maxZoom)
    }

    @Test
    fun `an unfinished chunk is not in the mask and the flight is not ready`() {
        val plan = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes)
        val single = FlightMaps("LHR", "JFK", stored(plan, done = emptySet(), partial = setOf(0)))
        assertTrue(single.maskTiles.isEmpty())
        assertFalse(single.isReady(DownloadKind.ROUTE))
        assertEquals(0, single.nextChunk(DownloadKind.ROUTE, plan.chunks.size))
        assertTrue(single.hasStarted(DownloadKind.ROUTE, 0))
    }

    @Test
    fun `chunks of a longer plan complete one by one`() {
        val plan = CorridorPlanner.plan(Routes.FRA, Routes.DEL, DownloadKind.ROUTE, sizes)
        val n = plan.chunks.size
        assertTrue("FRA-DEL needs several downloads ($n)", n >= 2)
        val first = FlightMaps("LHR", "JFK", stored(plan, done = setOf(0)))
        assertEquals(setOf(0), first.completeChunks(DownloadKind.ROUTE))
        assertEquals(1, first.nextChunk(DownloadKind.ROUTE, n))
        assertFalse(first.hasStarted(DownloadKind.ROUTE, 1))
        assertFalse(first.isReady(DownloadKind.ROUTE))
        assertEquals(plan.chunks[0].ownedBaseTiles, first.maskTiles)
        val all = FlightMaps("LHR", "JFK", stored(plan, done = (0 until n).toSet()))
        assertNull(all.nextChunk(DownloadKind.ROUTE, n))
        assertTrue(all.isReady(DownloadKind.ROUTE))
        assertEquals(plan.baseTiles, all.maskTiles)
        assertFalse(all.conflictsWith(DownloadKind.ROUTE, n))
        assertTrue(all.conflictsWith(DownloadKind.ROUTE, n + 1))
        assertFalse(all.conflictsWith(DownloadKind.DETAILED, 5))
    }

    @Test
    fun `a chunk with only its squares region is not complete`() {
        val plan = CorridorPlanner.plan(LHR, CDG, DownloadKind.ROUTE, sizes)
        val squaresOnly = stored(plan, done = setOf(0)).filter { !it.isStrip }
        assertTrue(squaresOnly.isNotEmpty())
        val maps = FlightMaps("LHR", "JFK", squaresOnly)
        assertTrue(maps.completeChunks(DownloadKind.ROUTE).isEmpty())
        assertTrue(maps.maskTiles.isEmpty())
    }

    @Test
    fun `a complete detailed download raises the max zoom to 9`() {
        val route = CorridorPlanner.plan(LHR, CDG, DownloadKind.ROUTE, sizes)
        val detailed = CorridorPlanner.plan(LHR, CDG, DownloadKind.DETAILED, sizes)
        val maps = FlightMaps("LHR", "JFK", stored(route, setOf(0)) + stored(detailed, setOf(0)))
        assertEquals(9, maps.maxZoom)
        assertTrue(maps.isReady(DownloadKind.DETAILED))
        // The detailed strip adds nothing to the mask.
        assertEquals(route.baseTiles, maps.maskTiles)
    }

    @Test
    fun `flights are grouped, sized and kept until 3 days after landing`() {
        val plan = CorridorPlanner.plan(LHR, CDG, DownloadKind.ROUTE, sizes)
        val lhrJfk = stored(plan, setOf(0)).map { it.copy(metadata = it.metadata.copy(createdAtMillis = 5_000L, landedAtMillis = 10_000L)) }
        val cdgLhr = stored(plan, setOf(0)).map { it.copy(id = it.id + 100, metadata = it.metadata.copy(origin = "CDG", destination = "LHR")) }
        val flights = FlightMaps.byFlight(cdgLhr + lhrJfk)
        assertEquals(listOf("LHR" to "JFK", "CDG" to "LHR"), flights.map { it.origin to it.destination })
        assertEquals(plan.chunks[0].estimatedBytes, flights[0].bytes)
        assertEquals(10_000L, flights[0].landedAtMillis)
        assertEquals(template, flights[0].tileUrlTemplate)
        val threeDays = CorridorRetention.KEEP_AFTER_LANDING_MILLIS
        assertFalse(flights[0].isDueForDeletion(10_000L + threeDays - 1))
        assertTrue(flights[0].isDueForDeletion(10_000L + threeDays))
        // Never landed: kept.
        assertNull(flights[1].landedAtMillis)
        assertFalse(flights[1].isDueForDeletion(Long.MAX_VALUE))
    }

    @Test
    fun `stored downloads planned with another chunk count are not mixed`() {
        val plan = CorridorPlanner.plan(LHR, CDG, DownloadKind.ROUTE, sizes)
        val regions = stored(plan, setOf(0))
        val other = regions.map { it.copy(id = it.id + 50, metadata = it.metadata.copy(chunkCount = 2)) }
        val maps = FlightMaps("LHR", "JFK", regions + other)
        assertNull(maps.chunkCount(DownloadKind.ROUTE))
        assertTrue(maps.completeChunks(DownloadKind.ROUTE).isEmpty())
        assertTrue(maps.conflictsWith(DownloadKind.ROUTE, 1))
    }
}
