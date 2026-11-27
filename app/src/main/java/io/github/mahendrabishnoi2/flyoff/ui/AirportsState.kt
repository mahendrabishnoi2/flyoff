package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mahendrabishnoi2.flyoff.airports.AirportCatalog
import io.github.mahendrabishnoi2.flyoff.airports.AirportSearch
import java.io.IOException

/** The bundled airport list as the UI sees it. */
sealed interface AirportsState {
    data object Loading : AirportsState

    class Ready(val search: AirportSearch) : AirportsState

    class Failed(val message: String) : AirportsState
}

/** The airport list and a way to try again after a failure. */
class Airports(val state: AirportsState, val retry: () -> Unit)

/**
 * Loads the airport list once per process (see [AirportCatalog]); after rotation it is ready
 * at once. A failure (a bug: the list is inside the APK) is shown, with a retry.
 */
@Composable
fun rememberAirports(): Airports {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember {
        mutableStateOf(AirportCatalog.loaded?.let { AirportsState.Ready(it) } ?: AirportsState.Loading)
    }
    LaunchedEffect(attempt) {
        if (state is AirportsState.Ready) return@LaunchedEffect
        state = AirportsState.Loading
        state = try {
            AirportsState.Ready(AirportCatalog.load(context))
        } catch (e: IOException) {
            AirportsState.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // AirportFile.parse names the bad line.
            AirportsState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    return Airports(state, retry = { attempt++ })
}
