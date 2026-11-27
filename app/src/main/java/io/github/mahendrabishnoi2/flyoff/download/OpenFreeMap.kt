package io.github.mahendrabishnoi2.flyoff.download

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * How Flyoff talks to OpenFreeMap, within its terms for offline downloads: a unique User-Agent
 * naming the app, and at most [MAX_PARALLEL_REQUESTS] requests at a time. Every route map
 * request (MapLibre's tile requests and the TileJSON) goes through one [client].
 */
object OpenFreeMap {

    /** The planet TileJSON: its `tiles[0]` is the URL template of the build served now. */
    const val TILEJSON_URL: String = "https://tiles.openfreemap.org/planet"

    /** OpenFreeMap allows at most 4 parallel requests per download. */
    const val MAX_PARALLEL_REQUESTS: Int = 4

    /** Where a tile template must point: OpenFreeMap's planet tiles, over HTTPS. */
    private const val TILES_PREFIX = "https://tiles.openfreemap.org/planet/"

    /** The header OpenFreeMap sends that names the build it served, or "wildcard" for an unknown one. */
    const val DEBUG_HEADER: String = "x-ofm-debug"

    /** The User-Agent for every request: unique to Flyoff, with its version and where to find it. */
    fun userAgent(versionName: String): String = "Flyoff/$versionName (+https://github.com/mahendrabishnoi2/flyoff)"

    /**
     * The one HTTP client: [userAgent] replaces MapLibre's own User-Agent on every request, and
     * the dispatcher runs at most [MAX_PARALLEL_REQUESTS] at once (in total and per host).
     * [onBuildGone] is called (on an OkHttp thread) for a tile response showing that OpenFreeMap
     * no longer serves the pinned build ([isBuildGone]).
     */
    fun client(versionName: String, onBuildGone: () -> Unit = {}): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = MAX_PARALLEL_REQUESTS
            maxRequestsPerHost = MAX_PARALLEL_REQUESTS
        }
        val agent = userAgent(versionName)
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(
                Interceptor { chain ->
                    val response = chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
                    val path = response.request.url.encodedPath
                    if (path.endsWith(".pbf") && isBuildGone(response.code, response.header(DEBUG_HEADER))) onBuildGone()
                    response
                },
            )
            .build()
    }

    /**
     * True when a tile response shows the pinned build is gone: a 404, or OpenFreeMap's debug
     * header saying it answered with its "wildcard" build (what it does today for a build it no
     * longer has, instead of a 404; seen 2026-11-27).
     */
    fun isBuildGone(code: Int, debugHeader: String?): Boolean =
        code == 404 || debugHeader?.trim()?.startsWith("wildcard", ignoreCase = true) == true

    /**
     * The tile URL template in a TileJSON document, or null when it has none that points to
     * OpenFreeMap's planet tiles with `{z}`, `{x}` and `{y}` on one line.
     */
    fun tileTemplate(tileJson: String): String? {
        val template = try {
            JSONObject(tileJson).getJSONArray("tiles").getString(0)
        } catch (e: JSONException) {
            return null
        }
        val valid = template.startsWith(TILES_PREFIX) &&
            listOf("{z}", "{x}", "{y}").all { it in template } &&
            template.none { it == '\n' || it == '\r' || it == ' ' }
        return template.takeIf { valid }
    }

    /**
     * Fetches the TileJSON with [client] and returns its tile template. Blocking: call it off the
     * main thread, and only for a download the user started.
     *
     * @throws IOException on a network error, an HTTP error or a TileJSON without a valid template.
     */
    fun fetchTileTemplate(client: OkHttpClient): String {
        client.newCall(Request.Builder().url(TILEJSON_URL).build()).execute().use { response: Response ->
            if (!response.isSuccessful) throw IOException("TileJSON: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("TileJSON: empty response")
            return tileTemplate(body) ?: throw IOException("TileJSON: no OpenFreeMap tile template")
        }
    }
}
