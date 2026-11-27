package io.github.mahendrabishnoi2.flyoff.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import io.github.mahendrabishnoi2.flyoff.BuildConfig
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.map.CORRIDOR_STYLE_ASSET
import io.github.mahendrabishnoi2.flyoff.map.corridorRegionStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a route map download didn't start or stopped, for the UI to explain. */
enum class DownloadProblem {
    /** Not on Wi-Fi (and mobile data not allowed, or the detailed map). */
    WIFI_REQUIRED,

    /** No network, or OpenFreeMap couldn't be reached. */
    NO_CONNECTION,

    /** MapLibre needed more tiles or bytes than OpenFreeMap allows per download. */
    OVER_LIMIT,

    /** OpenFreeMap no longer serves the map build this flight's maps were downloaded from. */
    BUILD_GONE,

    /** Storing the map failed (MapLibre's database). */
    STORAGE,
}

/** The download that is running now. */
data class RunningDownload(
    val origin: String,
    val destination: String,
    val kind: DownloadKind,
    val chunkIndex: Int,
    val chunkCount: Int,
    /** The planner's estimate for this download (an upper bound), in raw bytes. */
    val estimatedBytes: Long,
    /** From 0 to 1. */
    val fraction: Double = 0.0,
    /** Bytes stored so far. */
    val bytes: Long = 0,
    /** MapLibre reported a network error and is retrying. */
    val retrying: Boolean = false,
)

/** What the UI shows about route maps. */
data class RouteMapsState(
    /** The stored maps have been read at least once. */
    val loaded: Boolean = false,
    val flights: List<FlightMaps> = emptyList(),
    val running: RunningDownload? = null,
    /** The last problem, and the flight it was for (origin to destination). */
    val problem: Pair<Pair<String, String>, DownloadProblem>? = null,
    /** The user allows route maps (not the detailed map) over mobile data. */
    val mobileDataAllowed: Boolean = false,
) {
    fun flight(origin: String, destination: String): FlightMaps? = flights.find { it.origin == origin && it.destination == destination }

    fun problemFor(origin: String, destination: String): DownloadProblem? = problem?.takeIf { it.first == (origin to destination) }?.second
}

/** One download the user asked for: chunk [chunk] of a [chunkCount]-chunk [kind] plan. */
data class DownloadRequest(
    val origin: String,
    val destination: String,
    val kind: DownloadKind,
    val chunk: CorridorChunk,
    val chunkCount: Int,
)

/**
 * Route map downloads for the whole process, with MapLibre's offline regions as the only store.
 *
 * Network rule: MapLibre runs disconnected ([MapLibre.setConnected] false) from [init] on, and is
 * connected only while a download the user started runs. Then the map on screen shows no
 * downloaded corridor (whose missing tiles MapLibre would fetch): [start] waits for the map to
 * report it via [setCorridorShown]. Every request goes through [OpenFreeMap.client].
 *
 * Downloads run while the app's process lives; one cut short (the app was closed) is continued
 * by downloading the same part again, which reuses its regions.
 */
// The KTX `edit {}` would need androidx.core:core-ktx as a direct dependency for one call.
// Plain android.util.Log: the download lines are read from logcat (CI greps them); no Timber.
@SuppressLint("UseKtx", "LogNotTimber")
object RouteMaps {
    private const val TAG = "Flyoff"
    private const val PREFS = "route_maps"
    private const val MOBILE_DATA = "mobile_data"

    private val mutableState = MutableStateFlow(RouteMapsState())
    val state: StateFlow<RouteMapsState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cancelRequested = false
    private var client: OkHttpClient? = null
    private val buildGone = AtomicBoolean(false)
    private val corridorShown = MutableStateFlow(false)

    /**
     * Sets up MapLibre's networking before any map or download: disconnected, and with Flyoff's
     * HTTP client. Call it on the main thread when the process starts the app; again is a no-op.
     */
    fun init(context: Context) {
        if (client != null) return
        MapLibre.getInstance(context.applicationContext)
        MapLibre.setConnected(false)
        val http = OpenFreeMap.client(BuildConfig.VERSION_NAME) { buildGone.set(true) }
        HttpRequestUtil.setOkHttpClient(http)
        client = http
        val allowed = prefs(context).getBoolean(MOBILE_DATA, false)
        mutableState.update { it.copy(mobileDataAllowed = allowed) }
    }

    /**
     * Flyoff's one HTTP client ([OpenFreeMap.client]: its User-Agent and request limit), also
     * for the flight data update. Call it on the main thread, like [init].
     */
    fun httpClient(context: Context): OkHttpClient {
        init(context)
        return requireNotNull(client)
    }

    /** The map reports whether its style shows the downloaded corridor (whose tiles MapLibre could fetch). */
    fun setCorridorShown(shown: Boolean) {
        corridorShown.value = shown
    }

    fun setMobileDataAllowed(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(MOBILE_DATA, allowed).apply()
        mutableState.update { it.copy(mobileDataAllowed = allowed) }
    }

    fun clearProblem() {
        mutableState.update { it.copy(problem = null) }
    }

    /** Reads the stored maps, and deletes those due for deletion ([CorridorRetention]). */
    suspend fun refresh(context: Context) {
        init(context)
        val regions = try {
            listRegions(context)
        } catch (e: IOException) {
            Log.w(TAG, "listing offline regions failed: ${e.message}")
            return
        }
        val now = System.currentTimeMillis()
        val running = state.value.running
        val flights = FlightMaps.byFlight(regions.mapNotNull { it.second })
        for (flight in flights) {
            val busy = running != null && running.origin == flight.origin && running.destination == flight.destination
            if (!busy && flight.isDueForDeletion(now)) {
                Log.i(TAG, "route-maps-expired route=${flight.origin}-${flight.destination} landed=${flight.landedAtMillis}")
                deleteRegions(regions.filter { (_, s) -> s != null && s.metadata.origin == flight.origin && s.metadata.destination == flight.destination }.map { it.first })
            }
        }
        val kept = flights.filterNot { flight ->
            val busy = running != null && running.origin == flight.origin && running.destination == flight.destination
            !busy && flight.isDueForDeletion(now)
        }
        mutableState.update { it.copy(loaded = true, flights = kept) }
    }

    /** Starts [request], unless a download is running. The caller checked the network rule and the plan. */
    fun start(context: Context, request: DownloadRequest) {
        if (state.value.running != null) return
        val app = context.applicationContext
        init(app)
        cancelRequested = false
        mutableState.update {
            it.copy(
                running = RunningDownload(request.origin, request.destination, request.kind, request.chunk.index, request.chunkCount, request.chunk.estimatedBytes),
                problem = null,
            )
        }
        job = scope.launch { download(app, request) }
    }

    /** Stops the running download and deletes what it hadn't finished. */
    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    /** Deletes every stored map of the flight [origin] to [destination] (stopping its download first). */
    fun deleteFlight(context: Context, origin: String, destination: String) {
        val app = context.applicationContext
        scope.launch {
            val running = state.value.running
            if (running != null && running.origin == origin && running.destination == destination) {
                cancel()
                job?.join()
            }
            try {
                val regions = listRegions(app).filter { (_, s) -> s?.metadata?.origin == origin && s.metadata.destination == destination }
                deleteRegions(regions.map { it.first })
                Log.i(TAG, "route-maps-deleted route=$origin-$destination regions=${regions.size}")
            } catch (e: IOException) {
                Log.w(TAG, "deleting route maps failed: ${e.message}")
            }
            refresh(app)
        }
    }

    /**
     * Records that the flight [origin] to [destination] landed at [atMillis] ([LandingRule]) in
     * the metadata of each of its stored regions that has no landing time yet.
     */
    fun markLanded(context: Context, origin: String, destination: String, atMillis: Long) {
        val app = context.applicationContext
        scope.launch {
            init(app)
            try {
                for ((region, stored) in listRegions(app)) {
                    val metadata = stored?.metadata ?: continue
                    if (metadata.origin != origin || metadata.destination != destination || metadata.landedAtMillis != null) continue
                    updateMetadata(region, metadata.copy(landedAtMillis = atMillis))
                }
                Log.i(TAG, "route-maps-landed route=$origin-$destination at=$atMillis")
            } catch (e: IOException) {
                Log.w(TAG, "recording the landing failed: ${e.message}")
            }
            refresh(app)
        }
    }

    // ---- The download ----

    /** A download stopped for [problem]. */
    private class Stopped(val problem: DownloadProblem, message: String) : Exception(message)

    private suspend fun download(context: Context, request: DownloadRequest) {
        val label = "route=${request.origin}-${request.destination} kind=${request.kind} part=${request.chunk.index + 1}/${request.chunkCount}"
        var regions: List<OfflineRegion> = emptyList()
        var networkWatch: ConnectivityManager.NetworkCallback? = null
        var problem: DownloadProblem? = null
        try {
            // The map must not show the corridor while MapLibre may use the network.
            withTimeoutOrNull(5_000) { corridorShown.first { !it } } ?: throw Stopped(DownloadProblem.STORAGE, "the map still shows the corridor")
            val mobileData = state.value.mobileDataAllowed
            val network = networkKind(context)
            if (!NetworkRule.allows(request.kind, network, mobileData)) {
                throw Stopped(if (network == NetworkKind.NONE) DownloadProblem.NO_CONNECTION else DownloadProblem.WIFI_REQUIRED, "network $network")
            }
            val pinned = state.value.flight(request.origin, request.destination)?.tileUrlTemplate
            val template = pinned ?: try {
                withContext(Dispatchers.IO) { OpenFreeMap.fetchTileTemplate(requireNotNull(client)) }
            } catch (e: IOException) {
                throw Stopped(DownloadProblem.NO_CONNECTION, "TileJSON: ${e.message}")
            }
            Log.i(TAG, "download-start $label template=$template pinned=${pinned != null} network=$network")
            val styleUrl = regionStyle(context, template)
            regions = createRegions(context, request, template, styleUrl)

            buildGone.set(false)
            networkWatch = watchNetwork(context, request.kind, mobileData)
            MapLibre.setConnected(true)
            val planned = request.chunk.regions.map { it.tileCount }
            val progress = MutableList(regions.size) { RegionProgress.NONE }
            for ((i, region) in regions.withIndex()) {
                progress[i] = downloadRegion(region, "$label zooms=${request.chunk.regions[i].zooms.first}-${request.chunk.regions[i].zooms.last}", planned[i]) { now ->
                    progress[i] = now
                    DownloadGuard.check(progress)?.let { stop -> throw Stopped(DownloadProblem.OVER_LIMIT, stop.name) }
                    if (buildGone.get()) throw Stopped(DownloadProblem.BUILD_GONE, "OpenFreeMap no longer serves $template")
                    mutableState.update { s ->
                        s.copy(running = s.running?.copy(fraction = DownloadGuard.fraction(progress, planned), bytes = progress.sumOf { it.completedBytes }, retrying = false))
                    }
                }
            }
            Log.i(
                TAG,
                "download-done $label tiles=${progress.sumOf { it.completedTiles }} bytes=${progress.sumOf { it.completedBytes }} " +
                    "limit_tiles=${CorridorPlanner.MAX_TILES_PER_DOWNLOAD} limit_bytes=${CorridorPlanner.MAX_BYTES_PER_DOWNLOAD}",
            )
        } catch (e: Stopped) {
            Log.w(TAG, "download-stopped $label problem=${e.problem} reason=${e.message}")
            problem = e.problem
        } catch (e: IOException) {
            Log.w(TAG, "download-stopped $label problem=STORAGE reason=${e.message}")
            problem = DownloadProblem.STORAGE
        } catch (e: CancellationException) {
            Log.i(TAG, "download-cancelled $label")
            throw e
        } finally {
            MapLibre.setConnected(false)
            for (region in regions) {
                region.setObserver(null)
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
            networkWatch?.let { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            withContext(NonCancellable) {
                // A cancelled or over-limit download leaves nothing half-done behind.
                if (cancelRequested || problem == DownloadProblem.OVER_LIMIT) deleteUnfinished(regions, all = problem == DownloadProblem.OVER_LIMIT)
                mutableState.update { s ->
                    s.copy(running = null, problem = problem?.let { (request.origin to request.destination) to it })
                }
                refresh(context)
            }
        }
    }

    /**
     * The chunk's regions, squares first: the stored ones of an earlier, unfinished attempt, and
     * new ones for the rest, created in that order (see [FlightMaps]).
     */
    private suspend fun createRegions(context: Context, request: DownloadRequest, template: String, styleUrl: String): List<OfflineRegion> {
        val existing = listRegions(context).filter { (_, s) ->
            val m = s?.metadata
            m != null && m.origin == request.origin && m.destination == request.destination && m.kind == request.kind &&
                m.chunkIndex == request.chunk.index && m.chunkCount == request.chunkCount
        }
        val pixelRatio = context.resources.displayMetrics.density
        return request.chunk.regions.map { planned ->
            existing.firstOrNull { (_, s) -> s!!.metadata.minZoom == planned.zooms.first && s.metadata.maxZoom == planned.zooms.last }?.first
                ?: createRegion(
                    context,
                    OfflineGeometryRegionDefinition(styleUrl, geometry(planned.polygons), planned.zooms.first.toDouble(), planned.zooms.last.toDouble(), pixelRatio, false),
                    RegionMetadata(
                        origin = request.origin,
                        destination = request.destination,
                        kind = request.kind,
                        chunkIndex = request.chunk.index,
                        chunkCount = request.chunkCount,
                        minZoom = planned.zooms.first,
                        maxZoom = planned.zooms.last,
                        createdAtMillis = System.currentTimeMillis(),
                        tileUrlTemplate = template,
                    ),
                )
        }
    }

    /**
     * Downloads [region], calling [onProgress] on each status (it may throw to stop), and returns
     * its final progress. A region already complete is not activated.
     */
    private suspend fun downloadRegion(region: OfflineRegion, label: String, plannedTiles: Int, onProgress: (RegionProgress) -> Unit): RegionProgress {
        val before = status(region)
        if (before.isDone) {
            Log.i(TAG, "download-region-done id=${region.id} $label tiles=${before.completedTileCount} tile_bytes=${before.completedTileSize} bytes=${before.completedResourceSize} stored=true")
            return before.toProgress().also(onProgress)
        }
        return suspendCancellableCoroutine { cont ->
            var logged = false
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (!cont.isActive) return
                    if (!logged && status.isRequiredResourceCountPrecise) {
                        logged = true
                        val tiles = status.toProgress().requiredTiles
                        Log.i(TAG, "download-region id=${region.id} $label planned_tiles=$plannedTiles required_tiles=$tiles required_resources=${status.requiredResourceCount}")
                    }
                    val progress = status.toProgress()
                    try {
                        onProgress(progress)
                    } catch (e: Stopped) {
                        cont.resumeWithException(e)
                        return
                    }
                    if (status.isDone) {
                        Log.i(TAG, "download-region-done id=${region.id} $label tiles=${status.completedTileCount} tile_bytes=${status.completedTileSize} bytes=${status.completedResourceSize}")
                        cont.resume(progress)
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    // MapLibre retries network errors itself.
                    Log.w(TAG, "download-region-error id=${region.id} $label reason=${error.reason} message=${error.message}")
                    if (!cont.isActive) return
                    if (buildGone.get()) {
                        cont.resumeWithException(Stopped(DownloadProblem.BUILD_GONE, error.message))
                        return
                    }
                    mutableState.update { s -> s.copy(running = s.running?.copy(retrying = true)) }
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    if (cont.isActive) cont.resumeWithException(Stopped(DownloadProblem.OVER_LIMIT, "MapLibre tile limit $limit"))
                }
            })
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
    }

    /** Stops the download when the phone leaves Wi-Fi (or loses mobile data it may use). */
    private fun watchNetwork(context: Context, kind: DownloadKind, mobileData: Boolean): ConnectivityManager.NetworkCallback {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!NetworkRule.allows(kind, capabilities.kind(), mobileData)) {
                    scope.launch {
                        Log.i(TAG, "download network no longer allowed: ${capabilities.kind()}")
                        if (state.value.running != null) {
                            val running = state.value.running!!
                            job?.cancel(CancellationException("network"))
                            job?.join()
                            mutableState.update { it.copy(problem = (running.origin to running.destination) to DownloadProblem.WIFI_REQUIRED) }
                        }
                    }
                }
            }
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        return callback
    }

    // ---- MapLibre's offline database ----

    /** Every offline region, with what Flyoff reads from it (null when it isn't Flyoff's). */
    private suspend fun listRegions(context: Context): List<Pair<OfflineRegion, StoredRegion?>> {
        val regions: Array<OfflineRegion> = suspendCancellableCoroutine { cont ->
            OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions ?: emptyArray())
                override fun onError(error: String) = cont.resumeWithException(IOException(error))
            })
        }
        return regions.map { region ->
            val metadata = RegionMetadata.decode(region.metadata)
            val stored = metadata?.let {
                val status = status(region)
                StoredRegion(region.id, it, status.isDone, status.completedResourceSize, polygons(region))
            }
            region to stored
        }
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus = suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status != null) cont.resume(status) else cont.resumeWithException(IOException("no status"))
            }

            override fun onError(error: String?) = cont.resumeWithException(IOException(error))
        })
    }

    private suspend fun createRegion(context: Context, definition: OfflineGeometryRegionDefinition, metadata: RegionMetadata): OfflineRegion =
        suspendCancellableCoroutine { cont ->
            OfflineManager.getInstance(context).createOfflineRegion(definition, metadata.encode(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
                override fun onError(error: String) = cont.resumeWithException(IOException(error))
            })
        }

    private suspend fun updateMetadata(region: OfflineRegion, metadata: RegionMetadata) = suspendCancellableCoroutine { cont ->
        region.updateMetadata(metadata.encode(), object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
            override fun onUpdate(metadata: ByteArray) = cont.resume(Unit)
            override fun onError(error: String) = cont.resumeWithException(IOException(error))
        })
    }

    private suspend fun deleteRegions(regions: List<OfflineRegion>) {
        for (region in regions) {
            suspendCancellableCoroutine { cont ->
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) = cont.resumeWithException(IOException(error))
                })
            }
        }
    }

    /** Deletes the incomplete ones of [regions], or [all] of them. */
    private suspend fun deleteUnfinished(regions: List<OfflineRegion>, all: Boolean) {
        try {
            deleteRegions(regions.filter { all || !status(it).isDone })
        } catch (e: IOException) {
            Log.w(TAG, "deleting an unfinished download failed: ${e.message}")
        }
    }

    /**
     * The offline regions' style for [template] ([corridorRegionStyle]), written to app storage
     * and also put in MapLibre's database under its `file://` URL: the offline downloader finds
     * a resource there before it would ask the network, which may not read local files.
     */
    private suspend fun regionStyle(context: Context, template: String): String {
        val json = withContext(Dispatchers.IO) {
            val corridor = context.assets.open(CORRIDOR_STYLE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val style = corridorRegionStyle(corridor, template)
            val dir = File(context.filesDir, "route-maps").apply { mkdirs() }
            val file = File(dir, "style-%08x.json".format(template.hashCode()))
            file.writeText(style)
            Uri.fromFile(file).toString() to style
        }
        OfflineManager.getInstance(context).putResourceWithUrl(json.first, json.second.toByteArray(Charsets.UTF_8), 0, 0, null, false)
        return json.first
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kind of network the phone would download over now. */
    fun networkKind(context: Context): NetworkKind {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetworkKind.NONE
        return capabilities.kind()
    }

    private fun NetworkCapabilities.kind(): NetworkKind = when {
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.UNMETERED
        else -> NetworkKind.METERED
    }

    /** Complete, and MapLibre knows exactly what complete means (its style is parsed). */
    private val OfflineRegionStatus.isDone: Boolean
        get() = isComplete && isRequiredResourceCountPrecise && requiredResourceCount > 0

    private fun OfflineRegionStatus.toProgress() = RegionProgress(
        completedTiles = completedTileCount,
        completedResources = completedResourceCount,
        completedBytes = completedResourceSize,
        requiredResources = requiredResourceCount,
        requiredPrecise = isRequiredResourceCountPrecise,
        complete = isDone,
    )

    private fun geometry(polygons: List<List<LatLon>>): Geometry =
        MultiPolygon.fromLngLats(polygons.map { ring -> listOf(ring.map { Point.fromLngLat(it.longitude, it.latitude) }) })

    /** The outer rings of [region]'s geometry. */
    private fun polygons(region: OfflineRegion): List<List<LatLon>> {
        val geometry = (region.definition as? OfflineGeometryRegionDefinition)?.geometry
        val rings = when (geometry) {
            is MultiPolygon -> geometry.coordinates().mapNotNull { it.firstOrNull() }
            is Polygon -> listOfNotNull(geometry.coordinates().firstOrNull())
            else -> emptyList()
        }
        return rings.map { ring -> ring.map { LatLon(it.latitude(), it.longitude()) } }
    }
}
