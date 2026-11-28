package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mahendrabishnoi2.flyoff.routes.RouteCatalog
import io.github.mahendrabishnoi2.flyoff.routes.RouteData
import java.io.IOException

/** The flight routes as the UI sees them. */
sealed interface RoutesState {
    data object Loading : RoutesState

    class Ready(val data: RouteData) : RoutesState

    class Failed(val message: String) : RoutesState
}

/** Loads the flight routes once per process (see [RouteCatalog]), and follows updates. */
@Composable
fun rememberRoutes(): RoutesState {
    val context = LocalContext.current
    val data by RouteCatalog.data.collectAsStateWithLifecycle()
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            RouteCatalog.load(context)
        } catch (e: IOException) {
            // The bundled file is inside the APK: this is a bug.
            failure = e.message ?: e.javaClass.simpleName
        }
    }
    val loaded = data
    return when {
        loaded != null -> RoutesState.Ready(loaded)
        failure != null -> RoutesState.Failed(failure!!)
        else -> RoutesState.Loading
    }
}
