package io.github.mahendrabishnoi2.flyoff.routes

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.mahendrabishnoi2.flyoff.download.DownloadKind
import io.github.mahendrabishnoi2.flyoff.download.NetworkKind
import io.github.mahendrabishnoi2.flyoff.download.NetworkRule
import io.github.mahendrabishnoi2.flyoff.download.RouteMaps
import io.github.mahendrabishnoi2.flyoff.tracking.Tracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Why a flight data update didn't happen. */
enum class RouteUpdateProblem {
    /** Not on Wi-Fi, and mobile data isn't allowed for downloads. */
    WIFI_REQUIRED,

    /** No network, or the release couldn't be reached. */
    NO_CONNECTION,

    /** The manifest or the file didn't pass the checks; the routes in use are kept. */
    BAD_FILE,

    /** The file couldn't be stored on the phone. */
    STORAGE,

    /** Tracking is on: Flyoff uses no network then. */
    TRACKING,
}

/** Where the user's flight data update is. */
sealed interface RouteUpdateState {
    data object Idle : RouteUpdateState

    data object Checking : RouteUpdateState

    /** The routes in use, dated [dateSeconds], are the newest published. */
    data class UpToDate(val dateSeconds: Long) : RouteUpdateState

    /** Newer data is published: its date and size, for the user to decide. */
    data class Available(val manifest: RouteManifest) : RouteUpdateState

    data class Downloading(val manifest: RouteManifest) : RouteUpdateState

    data class Updated(val dateSeconds: Long) : RouteUpdateState

    data class Failed(val problem: RouteUpdateProblem) : RouteUpdateState
}

/**
 * The flight data update, started only by the user: [check] fetches the manifest from Flyoff's
 * own release ([RouteUpdate]) and, when it offers newer data, shows its date and size;
 * [download] then fetches the file, verifies it and stores it ([RouteCatalog] uses it from then
 * on). Both follow the route maps' network rule (Wi-Fi unless mobile data is allowed) through
 * the same HTTP client and User-Agent ([RouteMaps.httpClient]), and refuse to run while tracking.
 */
// Plain android.util.Log, like the route map downloads.
@SuppressLint("LogNotTimber")
object RouteUpdates {
    private const val TAG = "Flyoff"

    private val mutableState = MutableStateFlow<RouteUpdateState>(RouteUpdateState.Idle)
    val state: StateFlow<RouteUpdateState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private class Stopped(val problem: RouteUpdateProblem, message: String) : Exception(message)

    /** Looks for newer flight data (the user tapped "Update flight data"). */
    fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            mutableState.value = RouteUpdateState.Checking
            mutableState.value = run("check") {
                val current = RouteCatalog.load(app).dateSeconds
                val text = fetch(app, RouteUpdate.MANIFEST_URL, RouteUpdate.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                val manifest = RouteUpdate.parseManifest(text) ?: throw Stopped(RouteUpdateProblem.BAD_FILE, "bad manifest")
                Log.i(TAG, "routes-update-manifest date=${manifest.dateSeconds} size=${manifest.size} current=$current")
                if (RouteUpdate.isNewer(manifest, current)) RouteUpdateState.Available(manifest) else RouteUpdateState.UpToDate(current)
            }
        }
    }

    /** Downloads, verifies and stores the data that [check] found (the user tapped Download). */
    fun download(context: Context) {
        val manifest = (state.value as? RouteUpdateState.Available)?.manifest ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            mutableState.value = RouteUpdateState.Downloading(manifest)
            mutableState.value = run("download") {
                val current = RouteCatalog.load(app).dateSeconds
                val bytes = fetch(app, RouteUpdate.FILE_URL, manifest.size)
                val data = withContext(Dispatchers.Default) {
                    try {
                        RouteUpdate.verify(bytes, manifest, current)
                    } catch (e: IOException) {
                        throw Stopped(RouteUpdateProblem.BAD_FILE, e.message ?: "verify")
                    }
                }
                withContext(Dispatchers.IO) {
                    try {
                        RouteUpdate.store(bytes, RouteCatalog.downloadedFile(app))
                    } catch (e: IOException) {
                        throw Stopped(RouteUpdateProblem.STORAGE, e.message ?: "store")
                    }
                }
                RouteCatalog.replace(data)
                Log.i(TAG, "routes-update-done date=${data.dateSeconds} flights=${data.flightCount} bytes=${bytes.size}")
                RouteUpdateState.Updated(data.dateSeconds)
            }
        }
    }

    /** Back to the "Update flight data" button (Not now, or after a message). */
    fun dismiss() {
        if (job?.isActive == true) return
        mutableState.value = RouteUpdateState.Idle
    }

    private suspend fun run(step: String, block: suspend () -> RouteUpdateState): RouteUpdateState = try {
        block()
    } catch (e: Stopped) {
        Log.w(TAG, "routes-update-stopped step=$step problem=${e.problem} reason=${e.message}")
        RouteUpdateState.Failed(e.problem)
    } catch (e: IOException) {
        // Only the bundled file failing to load gets here: a bug, but the update didn't happen.
        Log.w(TAG, "routes-update-stopped step=$step problem=BAD_FILE reason=${e.message}")
        RouteUpdateState.Failed(RouteUpdateProblem.BAD_FILE)
    } catch (e: CancellationException) {
        throw e
    }

    /**
     * GETs [url] (at most [maxBytes]) after checking the rules: not while tracking, and only on a
     * network the route maps may use. Stops if tracking starts meanwhile.
     */
    private suspend fun fetch(context: Context, url: String, maxBytes: Long): ByteArray {
        if (Tracker.state.value.active) throw Stopped(RouteUpdateProblem.TRACKING, "tracking")
        val client = RouteMaps.httpClient(context)
        val network = RouteMaps.networkKind(context)
        if (!NetworkRule.allows(DownloadKind.ROUTE, network, RouteMaps.state.value.mobileDataAllowed)) {
            throw Stopped(if (network == NetworkKind.NONE) RouteUpdateProblem.NO_CONNECTION else RouteUpdateProblem.WIFI_REQUIRED, "network $network")
        }
        return withContext(Dispatchers.IO) {
            try {
                get(client, url, maxBytes)
            } catch (e: Stopped) {
                throw e
            } catch (e: IOException) {
                throw Stopped(RouteUpdateProblem.NO_CONNECTION, "$url: ${e.message}")
            }
        }
    }

    private fun get(client: OkHttpClient, url: String, maxBytes: Long): ByteArray {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            if (body.contentLength() > maxBytes) throw Stopped(RouteUpdateProblem.BAD_FILE, "$url: ${body.contentLength()} bytes, more than $maxBytes")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1 shl 16)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw Stopped(RouteUpdateProblem.BAD_FILE, "$url: more than $maxBytes bytes")
                    if (Tracker.state.value.active) throw Stopped(RouteUpdateProblem.TRACKING, "tracking started")
                }
            }
            return out.toByteArray()
        }
    }
}
