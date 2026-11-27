package io.github.mahendrabishnoi2.flyoff.airports

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads the airport list bundled in the APK (no network). */
object AirportAssets {

    private const val PATH = "airports/airports.tsv"

    /** Reads and parses `assets/airports/airports.tsv` off the main thread. */
    suspend fun load(context: Context): List<Airport> = withContext(Dispatchers.IO) {
        val text = context.assets.open(PATH).use { it.readBytes().toString(Charsets.UTF_8) }
        AirportFile.parse(text)
    }
}
