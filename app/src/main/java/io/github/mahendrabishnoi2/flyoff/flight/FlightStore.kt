package io.github.mahendrabishnoi2.flyoff.flight

import android.annotation.SuppressLint
import android.content.Context

/**
 * Keeps the chosen flight (two IATA codes) on the phone, so it is still set after a restart,
 * rotation or process death. Nothing else about the flight is stored.
 */
// The KTX `edit {}` would need androidx.core:core-ktx as a direct dependency for two calls.
@SuppressLint("UseKtx")
class FlightStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The stored flight, or null when none is set (or what is stored isn't a valid pair). */
    fun load(): FlightCodes? = FlightCodes.parse(prefs.getString(ORIGIN, null), prefs.getString(DESTINATION, null))

    fun save(codes: FlightCodes) {
        prefs.edit().putString(ORIGIN, codes.origin).putString(DESTINATION, codes.destination).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "flight"
        const val ORIGIN = "origin"
        const val DESTINATION = "destination"
    }
}
