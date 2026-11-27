package io.github.mahendrabishnoi2.flyoff.map

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the offline promise: the bundled style may only point at files inside
 * the APK's assets, and every one of those files must exist.
 */
class StyleAssetsTest {

    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val assets = File("src/main/assets")
    private val style = JSONObject(assetFile(STYLE_URI).readText())

    /** MapLibre asks for glyphs in 256-codepoint ranges; the bundled fonts cover Latin, Greek and Cyrillic. */
    private val glyphRanges = (0 until 6).map { "${it * 256}-${it * 256 + 255}" }

    @Test
    fun `style loads from assets`() {
        assertTrue("missing ${assetFile(STYLE_URI)}", assetFile(STYLE_URI).isFile)
        assertEquals(8, style.getInt("version"))
    }

    @Test
    fun `every URL in the style is a local asset`() {
        val urls = mutableListOf<String>()
        collectUrls(style, urls)
        assertTrue("expected the source and glyph URLs, found $urls", urls.size >= 2)
        for (url in urls) {
            assertTrue("$url would need the network", url.startsWith("asset://") || url.startsWith("pmtiles://asset://"))
        }
    }

    @Test
    fun `every source file exists`() {
        val sources = style.getJSONObject("sources")
        assertTrue("style has no sources", sources.length() > 0)
        for (name in sources.keys()) {
            val source = sources.getJSONObject(name)
            val url = source.optString("url")
            assertTrue("source $name has no url", url.isNotEmpty())
            val file = assetFile(url.removePrefix("pmtiles://"))
            assertTrue("source $name: missing $file", file.isFile)
            if (url.startsWith("pmtiles://")) {
                val magic = file.inputStream().use { it.readNBytes(8) }
                assertEquals("source $name: not a PMTiles v3 file", "PMTiles\u0003", String(magic, Charsets.ISO_8859_1))
            }
        }
    }

    @Test
    fun `every font stack the layers use has all glyph ranges`() {
        val glyphs = style.getString("glyphs")
        val stacks = fontStacks()
        assertTrue("no layer uses text", stacks.isNotEmpty())
        for (stack in stacks) {
            for (range in glyphRanges) {
                val url = glyphs.replace("{fontstack}", stack).replace("{range}", range)
                val file = assetFile(url)
                assertTrue("missing glyphs $file", file.isFile && file.length() > 0)
            }
        }
    }

    @Test
    fun `the route goes under every label`() {
        val layers = style.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        val below = ids.indexOf(ROUTE_BELOW_LAYER)
        assertTrue("style has no layer $ROUTE_BELOW_LAYER for the route to go under", below >= 0)
        for (i in 0 until below) {
            val layer = layers.getJSONObject(i)
            assertTrue("label layer ${layer.getString("id")} is under the route", layer.getString("type") != "symbol")
        }
    }

    @Test
    fun `the airport label font is bundled`() {
        val glyphs = style.getString("glyphs")
        for (range in glyphRanges) {
            val file = assetFile(glyphs.replace("{fontstack}", AIRPORT_LABEL_FONT).replace("{range}", range))
            assertTrue("missing glyphs $file", file.isFile && file.length() > 0)
        }
    }

    private fun assetFile(url: String): File {
        assertTrue("$url is not an asset:// URL", url.startsWith("asset://"))
        return File(assets, url.removePrefix("asset://"))
    }

    /** The font stacks of all symbol layers, as MapLibre names them in glyph URLs. */
    private fun fontStacks(): Set<String> {
        val stacks = mutableSetOf<String>()
        val layers = style.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val fonts = layer.optJSONObject("layout")?.opt("text-font") ?: continue
            if (fonts !is JSONArray || (0 until fonts.length()).any { fonts.opt(it) !is String }) {
                fail("layer ${layer.getString("id")}: text-font must be a list of font names so this test can check it")
            }
            fonts as JSONArray
            stacks += (0 until fonts.length()).joinToString(",") { fonts.getString(it) }
        }
        return stacks
    }

    /** Every string that looks like a URL, except the attribution text (its links open only on a tap). */
    private fun collectUrls(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> for (key in value.keys()) {
                if (key != "attribution") collectUrls(value.get(key), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectUrls(value.get(i), out)
            is String -> if ("://" in value) out += value
        }
    }
}
