package io.github.mahendrabishnoi2.flyoff.credits

import androidx.annotation.StringRes
import io.github.mahendrabishnoi2.flyoff.R

/** Where a credit is listed on the credits screen, in this order. */
enum class CreditGroup(@StringRes val title: Int) {
    APP(R.string.credits_group_app),
    MAP(R.string.credits_group_map),
    AIRPORTS(R.string.credits_group_airports),
    ROUTES(R.string.credits_group_routes),
    FONTS(R.string.credits_group_fonts),
    LIBRARIES(R.string.credits_group_libraries),
}

/**
 * A licence: its short name, where it is published, and its full text in the APK's assets
 * (null only where there is no licence text to give: public domain, or a service's terms).
 */
data class Licence(@StringRes val label: Int, val url: String, val textAsset: String? = null)

/**
 * One bundled data source, font or library: its name and copyright notice (proper names and
 * legal text, so not translated), what Flyoff uses it for, its licence and its home page.
 *
 * @param modules for libraries, the Maven modules it covers ("group:name", `*` matches any
 *   text), so a test can check every dependency in `app/build.gradle.kts` has a credit.
 */
data class Credit(
    val group: CreditGroup,
    val name: String,
    val notice: String?,
    @StringRes val usedFor: Int,
    val licence: Licence,
    val url: String,
    val modules: List<String> = emptyList(),
)

/**
 * Everything Flyoff ships that someone else made, for the credits screen. The licence texts in
 * `assets/licenses/` are verbatim copies from:
 *
 * - `GPL-3.0.txt`: this repository's `LICENSE` (a test keeps them equal).
 * - `Apache-2.0.txt`: https://www.apache.org/licenses/LICENSE-2.0.txt
 * - `MPL-2.0.txt`: https://www.mozilla.org/media/MPL/2.0/index.txt
 * - `ODbL-1.0.txt`: https://opendatacommons.org/licenses/odbl/odbl-10.txt
 * - `CC-BY-4.0.txt`: https://creativecommons.org/licenses/by/4.0/legalcode.txt
 * - `CC0-1.0.txt`: https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt
 * - `OurAirports-Unlicense.txt`: `LICENSE` of github.com/davidmegginson/ourairports-data
 * - `MapLibre-Native-Android.md`: `platform/android/LICENSE.md` of maplibre-native at tag
 *   android-v13.8.0 (MapLibre's licence and those of the components in its native library)
 * - `MapLibre-Java-MIT.txt`: `LICENSE` of maplibre-java at tag 6.0.1
 * - `MapLibre-Gestures-Android.md`: `LICENSE.md` of maplibre-gestures-android at tag 0.0.4
 *
 * The SIL OFL text is the fonts' own `assets/fonts/OFL.txt`.
 *
 * The libraries are those in the release APK: the runtime classpath
 * (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`) less what R8 removes
 * entirely (check `app/build/outputs/mapping/release/mapping.txt`). Annotation-only modules
 * (JSpecify, JetBrains annotations) and kotlinx.serialization don't reach the APK.
 */
object Credits {

    const val SOURCE_URL = "https://github.com/mahendrabishnoi2/flyoff"

    val GPL_3 = Licence(R.string.licence_gpl_3, "https://www.gnu.org/licenses/gpl-3.0.html", "licenses/GPL-3.0.txt")
    val ODBL = Licence(R.string.licence_odbl, "https://opendatacommons.org/licenses/odbl/1-0/", "licenses/ODbL-1.0.txt")
    val CC_BY_4 = Licence(R.string.licence_cc_by_4, "https://creativecommons.org/licenses/by/4.0/", "licenses/CC-BY-4.0.txt")
    val CC0 = Licence(R.string.licence_cc0, "https://creativecommons.org/publicdomain/zero/1.0/", "licenses/CC0-1.0.txt")
    val UNLICENSE = Licence(R.string.licence_unlicense, "https://unlicense.org/", "licenses/OurAirports-Unlicense.txt")
    val OFL = Licence(R.string.licence_ofl, "https://openfontlicense.org/", "fonts/OFL.txt")
    val APACHE_2 = Licence(R.string.licence_apache_2, "https://www.apache.org/licenses/LICENSE-2.0", "licenses/Apache-2.0.txt")
    val MPL_2 = Licence(R.string.licence_mpl_2, "https://mozilla.org/MPL/2.0/", "licenses/MPL-2.0.txt")
    val MAPLIBRE_NATIVE = Licence(
        R.string.licence_bsd_2,
        "https://github.com/maplibre/maplibre-native/blob/main/platform/android/LICENSE.md",
        "licenses/MapLibre-Native-Android.md",
    )
    val MAPLIBRE_JAVA = Licence(R.string.licence_mit, "https://github.com/maplibre/maplibre-java/blob/main/LICENSE", "licenses/MapLibre-Java-MIT.txt")
    val MAPLIBRE_GESTURES = Licence(
        R.string.licence_bsd_2,
        "https://github.com/maplibre/maplibre-gestures-android/blob/main/LICENSE.md",
        "licenses/MapLibre-Gestures-Android.md",
    )
    val NATURAL_EARTH_TERMS = Licence(R.string.licence_public_domain, "https://www.naturalearthdata.com/about/terms-of-use/")
    val OPENFREEMAP_TERMS = Licence(R.string.licence_terms_of_use, "https://openfreemap.org/tos/")

    val all: List<Credit> = listOf(
        Credit(
            CreditGroup.APP, "Flyoff", null, R.string.credits_flyoff,
            GPL_3, SOURCE_URL,
        ),

        Credit(
            CreditGroup.MAP, "OpenStreetMap", "© OpenStreetMap contributors", R.string.credits_openstreetmap,
            ODBL, "https://www.openstreetmap.org/copyright",
        ),
        Credit(
            CreditGroup.MAP, "OpenMapTiles", "© OpenMapTiles · MapTiler.com & OpenMapTiles contributors", R.string.credits_openmaptiles,
            CC_BY_4, "https://openmaptiles.org/",
        ),
        Credit(
            CreditGroup.MAP, "OpenFreeMap", null, R.string.credits_openfreemap,
            OPENFREEMAP_TERMS, "https://openfreemap.org/",
        ),
        Credit(
            CreditGroup.MAP, "Natural Earth", null, R.string.credits_natural_earth,
            NATURAL_EARTH_TERMS, "https://www.naturalearthdata.com/",
        ),

        Credit(
            CreditGroup.AIRPORTS, "OurAirports", null, R.string.credits_ourairports,
            UNLICENSE, "https://github.com/davidmegginson/ourairports-data",
        ),
        Credit(
            CreditGroup.AIRPORTS, "timezone-boundary-builder", "© OpenStreetMap contributors", R.string.credits_timezones,
            ODBL, "https://github.com/evansiroky/timezone-boundary-builder",
        ),

        Credit(
            CreditGroup.ROUTES, "VRS standing data", "Virtual Radar Server", R.string.credits_vrs,
            CC0, "https://github.com/vradarserver/standing-data",
        ),

        Credit(
            CreditGroup.FONTS, "Noto Sans", "Copyright 2022 The Noto Project Authors", R.string.credits_noto,
            OFL, "https://github.com/notofonts",
        ),

        Credit(
            CreditGroup.LIBRARIES, "MapLibre Native", "© MapLibre contributors, © MapTiler.com, © Mapbox", R.string.credits_maplibre_native,
            MAPLIBRE_NATIVE, "https://github.com/maplibre/maplibre-native",
            listOf("org.maplibre.gl:android-sdk-opengl"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "MapLibre Java", "© 2018 Mapbox", R.string.credits_maplibre_java,
            MAPLIBRE_JAVA, "https://github.com/maplibre/maplibre-java",
            listOf("org.maplibre.gl:android-sdk-geojson", "org.maplibre.gl:android-sdk-turf"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "MapLibre Gestures for Android", "© 2018 Mapbox, © 2024 MapLibre contributors", R.string.credits_maplibre_gestures,
            MAPLIBRE_GESTURES, "https://github.com/maplibre/maplibre-gestures-android",
            listOf("org.maplibre.gl:maplibre-android-gestures"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "Jetpack Compose and AndroidX", "© The Android Open Source Project", R.string.credits_androidx,
            APACHE_2, "https://developer.android.com/jetpack/androidx",
            listOf("androidx.*:*"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "Kotlin", "© 2010-2024 JetBrains s.r.o and respective authors and developers", R.string.credits_kotlin,
            APACHE_2, "https://kotlinlang.org/",
            listOf("org.jetbrains.kotlin:kotlin-stdlib*"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "kotlinx.coroutines", null, R.string.credits_coroutines,
            APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines",
            listOf("org.jetbrains.kotlinx:kotlinx-coroutines-*"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "OkHttp", "© 2019 Square, Inc.", R.string.credits_okhttp,
            APACHE_2, "https://github.com/square/okhttp",
            listOf("com.squareup.okhttp3:okhttp"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "Okio", "© 2013 Square, Inc.", R.string.credits_okio,
            APACHE_2, "https://github.com/square/okio",
            listOf("com.squareup.okio:okio*"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "Public Suffix List", null, R.string.credits_public_suffix,
            MPL_2, "https://publicsuffix.org/",
        ),
        Credit(
            CreditGroup.LIBRARIES, "Gson", "© 2008 Google Inc.", R.string.credits_gson,
            APACHE_2, "https://github.com/google/gson",
            listOf("com.google.code.gson:gson"),
        ),
        Credit(
            CreditGroup.LIBRARIES, "Timber", "© 2013 Jake Wharton", R.string.credits_timber,
            APACHE_2, "https://github.com/JakeWharton/timber",
            listOf("com.jakewharton.timber:timber"),
        ),
    )

    /** The credit, if any, that covers Maven module [groupAndName] ("group:name"). */
    fun forModule(groupAndName: String): Credit? = all.firstOrNull { credit ->
        credit.modules.any { pattern -> matches(pattern, groupAndName) }
    }

    private fun matches(pattern: String, text: String): Boolean =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(text)
}

/**
 * A licence text as paragraphs to show on a phone: split at blank lines, with each paragraph's
 * hard line breaks (licence files wrap at about 70 characters) and runs of spaces turned into
 * single spaces, so the text flows to the screen's width. The words are unchanged.
 */
fun licenceParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }

/** A link as people read it: no scheme, no "www.", no trailing slash. */
fun displayUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")
