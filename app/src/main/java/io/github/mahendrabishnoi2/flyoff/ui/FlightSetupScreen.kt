package io.github.mahendrabishnoi2.flyoff.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.airports.Airport
import io.github.mahendrabishnoi2.flyoff.airports.AirportSearch
import io.github.mahendrabishnoi2.flyoff.download.RouteMaps
import io.github.mahendrabishnoi2.flyoff.flight.Endpoint
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightDraft
import io.github.mahendrabishnoi2.flyoff.routes.FlightLookup
import io.github.mahendrabishnoi2.flyoff.routes.RouteLeg
import io.github.mahendrabishnoi2.flyoff.routes.RouteUpdates
import java.util.IllformedLocaleException
import java.util.Locale

private const val MAX_RESULTS = 30

/**
 * Full-screen flight setup: two ends (From, To) and one offline search field for the selected
 * end. Picking a result fills that end and moves on to the other one if it is still empty.
 * Choosing the same airport for both ends is refused with a message.
 *
 * Above them, an optional flight number suggests a route from the bundled route data
 * ([FlightNumberSection]); using it fills both ends, which the user then checks.
 * The whole screen scrolls as one, so it fits with the keyboard up and in landscape.
 *
 * Everything here survives rotation and process death (saved state); nothing is stored until
 * [onDone].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightSetupScreen(
    airports: AirportsState,
    onRetry: () -> Unit,
    current: FlightCodes?,
    onDone: (FlightCodes) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var origin by rememberSaveable { mutableStateOf(current?.origin) }
    var destination by rememberSaveable { mutableStateOf(current?.destination) }
    val draft = FlightDraft(origin, destination)
    var active by rememberSaveable { mutableStateOf(if (origin == null || destination != null) Endpoint.ORIGIN else Endpoint.DESTINATION) }
    var query by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf<String?>(null) }
    var flightNumber by rememberSaveable { mutableStateOf("") }
    val routes = rememberRoutes()
    val context = LocalContext.current
    val updateState by RouteUpdates.state.collectAsStateWithLifecycle()
    val routeMaps by RouteMaps.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }

    fun update(newDraft: FlightDraft) {
        origin = newDraft.origin
        destination = newDraft.destination
    }

    fun pick(airport: Airport) {
        val picked = draft.pick(active, airport.iata)
        if (picked == null) {
            rejected = airport.iata
            return
        }
        rejected = null
        update(picked)
        query = ""
        val other = if (active == Endpoint.ORIGIN) Endpoint.DESTINATION else Endpoint.ORIGIN
        val otherEmpty = if (other == Endpoint.ORIGIN) picked.origin == null else picked.destination == null
        if (otherEmpty) {
            active = other
            searchFocus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    fun useLeg(leg: RouteLeg) {
        update(FlightDraft(leg.origin.iata, leg.destination.iata))
        rejected = null
        query = ""
        active = Endpoint.ORIGIN
        keyboard?.hide()
    }

    fun select(end: Endpoint) {
        active = end
        query = ""
        rejected = null
        searchFocus.requestFocus()
        keyboard?.show()
    }

    BackHandler(onBack = onClose)
    val search = (airports as? AirportsState.Ready)?.search
    val routeData = (routes as? RoutesState.Ready)?.data
    val lookup = remember(flightNumber, routeData, search) {
        if (routeData == null || search == null) null else FlightLookup.of(flightNumber, routeData, search::byRouteCode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_up_flight)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    val codes = draft.toCodes()
                    Button(
                        onClick = {
                            keyboard?.hide()
                            if (codes != null) onDone(codes)
                        },
                        enabled = codes != null && search != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.done)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            FlightNumberSection(
                text = flightNumber,
                onTextChange = { flightNumber = it },
                routes = if (search == null && routes is RoutesState.Ready) RoutesState.Loading else routes,
                lookup = lookup,
                chosen = draft.toCodes(),
                onUse = ::useLeg,
                update = FlightDataUpdate(
                    state = updateState,
                    dateSeconds = (routes as? RoutesState.Ready)?.data?.dateSeconds,
                    mobileDataAllowed = routeMaps.mobileDataAllowed,
                    onCheck = { RouteUpdates.check(context) },
                    onDownload = { RouteUpdates.download(context) },
                    onDismiss = RouteUpdates::dismiss,
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EndpointRow(
                        label = stringResource(R.string.from),
                        airport = origin?.let { search?.byIata(it) },
                        code = origin,
                        selected = active == Endpoint.ORIGIN,
                        onClick = { select(Endpoint.ORIGIN) },
                    )
                    EndpointRow(
                        label = stringResource(R.string.to),
                        airport = destination?.let { search?.byIata(it) },
                        code = destination,
                        selected = active == Endpoint.DESTINATION,
                        onClick = { select(Endpoint.DESTINATION) },
                    )
                }
                IconButton(
                    onClick = {
                        update(draft.swapped())
                        rejected = null
                    },
                    enabled = origin != null || destination != null,
                ) {
                    Icon(painterResource(R.drawable.ic_swap), contentDescription = stringResource(R.string.swap_airports))
                }
            }
            rejected?.let {
                Text(
                    text = stringResource(R.string.same_airport, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    rejected = null
                },
                label = {
                    Text(stringResource(if (active == Endpoint.ORIGIN) R.string.search_origin else R.string.search_destination))
                },
                singleLine = true,
                trailingIcon = if (query.isEmpty()) null else {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search?.search(query, 1)?.firstOrNull()?.let(::pick) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(searchFocus),
            )
            // Here, in the same (sub)composition as the field, so the requester is attached.
            LaunchedEffect(search != null) {
                if (search != null && (origin == null || destination == null)) searchFocus.requestFocus()
            }
            when (airports) {
                AirportsState.Loading -> Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.airports_loading))
                }
                is AirportsState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.airports_error_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.airports_error_body, airports.message))
                    Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                is AirportsState.Ready -> Results(
                    search = airports.search,
                    query = query,
                    showClear = current != null && query.isBlank(),
                    onPick = ::pick,
                    onClear = onClear,
                )
            }
        }
    }
}

@Composable
private fun Results(
    search: AirportSearch,
    query: String,
    showClear: Boolean,
    onPick: (Airport) -> Unit,
    onClear: () -> Unit,
) {
    val results = remember(search, query) { search.search(query, MAX_RESULTS) }
    when {
        query.isBlank() -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.airport_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showClear) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.clear_flight)) }
            }
        }
        results.isEmpty() -> Text(
            stringResource(R.string.airport_no_results, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // At most MAX_RESULTS rows, in the screen's own scroll.
        else -> Column(Modifier.fillMaxWidth()) {
            for (airport in results) {
                key(airport.iata) {
                    AirportRow(airport, onClick = { onPick(airport) })
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One search result: the IATA code large, then the name and the city and country. */
@Composable
private fun AirportRow(airport: Airport, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val place = placeName(airport, locale)
    val description = "${airport.iata}, ${airport.name}, $place"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = airport.iata,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(airport.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                place,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One end of the flight; the selected end is outlined and is what the search fills. */
@Composable
private fun EndpointRow(label: String, airport: Airport?, code: String?, selected: Boolean, onClick: () -> Unit) {
    val value = when {
        airport != null -> "${airport.iata}  ${airport.name}"
        code != null -> code
        else -> stringResource(R.string.choose_airport)
    }
    val description = stringResource(R.string.endpoint_description, label, value)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (code == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "London, United Kingdom", in the user's language where the system knows the country. */
private fun placeName(airport: Airport, locale: Locale): String {
    val country = try {
        Locale.Builder().setRegion(airport.countryCode).build().getDisplayCountry(locale).ifBlank { airport.countryCode }
    } catch (_: IllformedLocaleException) {
        airport.countryCode
    }
    return if (airport.city.isBlank()) country else "${airport.city}, $country"
}
