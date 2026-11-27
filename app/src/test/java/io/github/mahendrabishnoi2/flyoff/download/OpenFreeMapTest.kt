package io.github.mahendrabishnoi2.flyoff.download

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenFreeMapTest {

    private val template = "https://tiles.openfreemap.org/planet/20261122_124456_pt/{z}/{x}/{y}.pbf"

    /** Sends [request] through [client] to a fake server (no network) that answers [code] with [headers]. */
    private fun exchange(
        client: okhttp3.OkHttpClient,
        request: Request,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): Request {
        var seen: Request? = null
        val server = Interceptor { chain ->
            seen = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_2)
                .code(code)
                .message("")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody())
                .build()
        }
        client.newBuilder().addInterceptor(server).build().newCall(request).execute().close()
        return seen!!
    }

    @Test
    fun `the user agent names Flyoff, its version and its home`() {
        assertEquals("Flyoff/0.1.0 (+https://github.com/mahendrabishnoi2/flyoff)", OpenFreeMap.userAgent("0.1.0"))
    }

    @Test
    fun `at most four requests run at once`() {
        val client = OpenFreeMap.client("0.1.0")
        assertEquals(4, client.dispatcher.maxRequests)
        assertEquals(4, client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `the client replaces MapLibre's user agent`() {
        val client = OpenFreeMap.client("0.1.0")
        val request = Request.Builder()
            .url("https://tiles.openfreemap.org/planet/20261122_124456_pt/6/31/21.pbf")
            .addHeader("User-Agent", "MapLibre Android/13.8.0")
            .build()
        val sent = exchange(client, request)
        assertEquals(listOf("Flyoff/0.1.0 (+https://github.com/mahendrabishnoi2/flyoff)"), sent.headers("User-Agent"))
    }

    @Test
    fun `a request without a user agent gets Flyoff's`() {
        val sent = exchange(OpenFreeMap.client("2.0"), Request.Builder().url(OpenFreeMap.TILEJSON_URL).build())
        assertEquals("Flyoff/2.0 (+https://github.com/mahendrabishnoi2/flyoff)", sent.header("User-Agent"))
    }

    @Test
    fun `a tile from OpenFreeMap's wildcard build reports the pinned build gone`() {
        var gone = 0
        val client = OpenFreeMap.client("0.1.0") { gone++ }
        val tile = Request.Builder().url("https://tiles.openfreemap.org/planet/20200101_000000_pt/0/0/0.pbf").build()
        exchange(client, tile, headers = mapOf("x-ofm-debug" to "specific PBF planet 20200101_000000_pt"))
        assertEquals(0, gone)
        exchange(client, tile, headers = mapOf("x-ofm-debug" to "wildcard PBF planet"))
        assertEquals(1, gone)
        exchange(client, tile, code = 404)
        assertEquals(2, gone)
        // Only tiles: the TileJSON is not a build.
        exchange(client, Request.Builder().url(OpenFreeMap.TILEJSON_URL).build(), code = 404)
        assertEquals(2, gone)
    }

    @Test
    fun `build gone rule`() {
        assertTrue(OpenFreeMap.isBuildGone(404, null))
        assertTrue(OpenFreeMap.isBuildGone(200, "wildcard PBF planet"))
        assertTrue(OpenFreeMap.isBuildGone(200, " Wildcard"))
        assertFalse(OpenFreeMap.isBuildGone(200, null))
        assertFalse(OpenFreeMap.isBuildGone(200, "specific PBF planet 20261122_124456_pt"))
        assertFalse(OpenFreeMap.isBuildGone(500, null))
    }

    @Test
    fun `the tile template comes from the TileJSON`() {
        val json = """{"tilejson":"3.0.0","tiles":["$template"],"vector_layers":[]}"""
        assertEquals(template, OpenFreeMap.tileTemplate(json))
    }

    @Test
    fun `a TileJSON without an OpenFreeMap planet template gives null`() {
        assertNull(OpenFreeMap.tileTemplate("""{"tiles":[]}"""))
        assertNull(OpenFreeMap.tileTemplate("""{"tilejson":"3.0.0"}"""))
        assertNull(OpenFreeMap.tileTemplate("not json"))
        assertNull(OpenFreeMap.tileTemplate("""{"tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"]}"""))
        assertNull(OpenFreeMap.tileTemplate("""{"tiles":["http://tiles.openfreemap.org/planet/x/{z}/{x}/{y}.pbf"]}"""))
        assertNull(OpenFreeMap.tileTemplate("""{"tiles":["https://tiles.openfreemap.org/planet/x/{z}/{y}.pbf"]}"""))
        assertNull(OpenFreeMap.tileTemplate("""{"tiles":["https://tiles.openfreemap.org/planet/x\n/{z}/{x}/{y}.pbf"]}"""))
    }

    @Test
    fun `fetching the TileJSON uses the client and checks the answer`() {
        fun clientAnswering(code: Int, body: String) = OpenFreeMap.client("0.1.0").newBuilder()
            .addInterceptor { chain ->
                assertEquals(OpenFreeMap.TILEJSON_URL, chain.request().url.toString())
                assertTrue(chain.request().header("User-Agent")!!.startsWith("Flyoff/"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(code).message("").body(body.toResponseBody()).build()
            }
            .build()
        assertEquals(template, OpenFreeMap.fetchTileTemplate(clientAnswering(200, """{"tiles":["$template"]}""")))
        for ((code, body) in listOf(503 to "", 200 to "{}", 200 to "<html>")) {
            try {
                OpenFreeMap.fetchTileTemplate(clientAnswering(code, body))
                throw AssertionError("expected an IOException for $code $body")
            } catch (expected: IOException) {
                // As documented.
            }
        }
    }
}
