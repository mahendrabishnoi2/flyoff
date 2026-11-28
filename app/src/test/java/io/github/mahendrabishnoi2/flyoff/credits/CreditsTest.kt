package io.github.mahendrabishnoi2.flyoff.credits

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CreditsTest {

    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val assets = File("src/main/assets")

    @Test
    fun `every credit has a name, what it is for, a licence and a web page`() {
        for (credit in Credits.all) {
            assertTrue("a credit has no name", credit.name.isNotBlank())
            assertTrue("${credit.name} says nothing about its use", credit.usedFor != 0)
            assertTrue("${credit.name} has no licence name", credit.licence.label != 0)
            assertTrue("${credit.name}: ${credit.url} isn't https", credit.url.startsWith("https://"))
            assertTrue("${credit.name}: ${credit.licence.url} isn't https", credit.licence.url.startsWith("https://"))
        }
        assertEquals("names must be unique", Credits.all.size, Credits.all.map { it.name }.toSet().size)
    }

    @Test
    fun `every group has credits, listed in group order`() {
        assertEquals(CreditGroup.entries.toList(), Credits.all.map { it.group }.distinct())
    }

    @Test
    fun `only public domain data and a service's terms come without a licence text`() {
        val withoutText = Credits.all.filter { it.licence.textAsset == null }.map { it.name }.toSet()
        assertEquals(setOf("Natural Earth", "OpenFreeMap"), withoutText)
    }

    @Test
    fun `every licence text is in the assets and not empty`() {
        for (credit in Credits.all) {
            val path = credit.licence.textAsset ?: continue
            val file = File(assets, path)
            assertTrue("${credit.name}: missing $file", file.isFile)
            assertTrue("${credit.name}: $file is almost empty", file.length() > 500)
        }
    }

    @Test
    fun `every bundled licence text is shown for some credit`() {
        val shown = Credits.all.mapNotNull { it.licence.textAsset }.toSet()
        val bundled = File(assets, "licenses").listFiles().orEmpty().map { "licenses/${it.name}" }
        assertTrue("no licence texts in $assets/licenses", bundled.isNotEmpty())
        for (path in bundled) assertTrue("$path is bundled but no credit shows it", path in shown)
    }

    @Test
    fun `the bundled GPL is this repository's licence`() {
        assertArrayEquals(File("../LICENSE").readBytes(), File(assets, Credits.GPL_3.textAsset!!).readBytes())
    }

    @Test
    fun `the data credits match the bundled NOTICE files`() {
        val airports = File(assets, "airports/NOTICE.txt").readText()
        assertTrue(airports.contains("OurAirports") && airports.contains("timezone-boundary-builder"))
        assertTrue(airports.contains("Open Database License (ODbL) 1.0"))
        assertTrue(File(assets, "routes/NOTICE.txt").readText().contains("CC0 1.0 Universal"))
        assertEquals(Credits.ODBL, Credits.all.single { it.name == "timezone-boundary-builder" }.licence)
        assertEquals(Credits.CC0, Credits.all.single { it.name == "VRS standing data" }.licence)
        assertTrue(File(assets, Credits.OFL.textAsset!!).readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
    }

    @Test
    fun `the map credits say what the on-map attribution says`() {
        val style = File(assets, "style/flyoff.json").readText()
        for (name in listOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap")) {
            assertTrue("the style's attribution doesn't name $name", style.contains(name))
            assertEquals(CreditGroup.MAP, Credits.all.single { it.name == name }.group)
        }
    }

    @Test
    fun `every library the app declares has a credit`() {
        val catalog = libraryModules(File("../gradle/libs.versions.toml").readText())
        val declared = Regex("""^\s*implementation\(libs\.([\w.]+)\)""", RegexOption.MULTILINE)
            .findAll(File("build.gradle.kts").readText())
            .map { it.groupValues[1].replace('.', '-') }
            .toList()
        assertTrue("found no implementation(libs.…) lines", declared.size >= 5)
        for (alias in declared) {
            val module = catalog[alias]
            assertNotNull("$alias isn't in the version catalog", module)
            assertNotNull("$module has no credit", Credits.forModule(module!!))
        }
    }

    @Test
    fun `module patterns match whole names`() {
        assertEquals("MapLibre Native", Credits.forModule("org.maplibre.gl:android-sdk-opengl")?.name)
        assertEquals("Jetpack Compose and AndroidX", Credits.forModule("androidx.compose.material3:material3")?.name)
        assertEquals("kotlinx.coroutines", Credits.forModule("org.jetbrains.kotlinx:kotlinx-coroutines-android")?.name)
        assertEquals(null, Credits.forModule("org.maplibre.gl:android-sdk-opengl-extra"))
        assertEquals(null, Credits.forModule("com.example:androidx"))
    }

    @Test
    fun `licence paragraphs split at blank lines and flow hard-wrapped lines`() {
        val text = "Title\r\n\r\n  First line\nwraps   here.\n \nSecond\tparagraph.\n\n\n"
        assertEquals(listOf("Title", "First line wraps here.", "Second paragraph."), licenceParagraphs(text))
        assertEquals(emptyList<String>(), licenceParagraphs("\n \n"))
    }

    @Test
    fun `licence paragraphs keep every word of the real texts`() {
        for (path in Credits.all.mapNotNull { it.licence.textAsset }.distinct()) {
            val text = File(assets, path).readText()
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertEquals(path, words, licenceParagraphs(text).flatMap { it.split(" ") })
        }
    }

    @Test
    fun `links read without the scheme`() {
        assertEquals("openstreetmap.org/copyright", displayUrl("https://www.openstreetmap.org/copyright"))
        assertEquals("openfreemap.org", displayUrl("https://openfreemap.org/"))
        assertEquals("github.com/mahendrabishnoi2/flyoff", displayUrl(Credits.SOURCE_URL))
    }

    /** "alias" to "group:name" for each library in a version catalog's [libraries] table. */
    private fun libraryModules(toml: String): Map<String, String> {
        val table = toml.substringAfter("[libraries]").substringBefore("\n[")
        return Regex("""^([\w-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)",\s*name\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(table)
            .associate { it.groupValues[1] to "${it.groupValues[2]}:${it.groupValues[3]}" }
    }
}
