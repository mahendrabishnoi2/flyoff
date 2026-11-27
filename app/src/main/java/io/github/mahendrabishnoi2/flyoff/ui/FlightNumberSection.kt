package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.routes.FlightLookup
import io.github.mahendrabishnoi2.flyoff.routes.RouteLeg
import io.github.mahendrabishnoi2.flyoff.routes.RouteSuggestion

/**
 * The optional flight number at the top of flight setup. What it finds is a suggestion the
 * user confirms ("Use this route", or picking a leg); the airport picker below stays the main
 * input and works the same whatever is typed here.
 *
 * [lookup] is what [text] finds, or null while the routes load or fail ([routes]). [chosen] is
 * the flight the airport picker holds now: once it is a suggested leg, the suggestion folds
 * into one line.
 */
@Composable
fun FlightNumberSection(
    text: String,
    onTextChange: (String) -> Unit,
    routes: RoutesState,
    lookup: FlightLookup?,
    chosen: FlightCodes?,
    onUse: (RouteLeg) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.flight_number_label)) },
            placeholder = { Text(stringResource(R.string.flight_number_placeholder)) },
            singleLine = true,
            trailingIcon = if (text.isEmpty()) null else {
                {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_flight_number))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isBlank()) return@Column
        // Announced as it changes, so a screen reader hears what the typed number found.
        val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when {
            routes is RoutesState.Loading -> Message(stringResource(R.string.flight_routes_loading), announce)
            routes is RoutesState.Failed -> Message(stringResource(R.string.flight_routes_error, routes.message), announce)
            lookup == null || lookup is FlightLookup.NotAFlightNumber -> Message(stringResource(R.string.flight_number_invalid), announce)
            lookup is FlightLookup.NotFound -> Message(stringResource(R.string.flight_not_found, lookup.flight.text), announce)
            lookup is FlightLookup.NoKnownAirports -> Message(
                stringResource(R.string.flight_unknown_airports, lookup.flight.text, lookup.stops.joinToString(" → ")),
                announce,
            )
            lookup is FlightLookup.Found -> {
                val used = lookup.suggestions.firstOrNull { s -> s.legs.any { it.codes == chosen } }
                if (used != null) {
                    Message(stringResource(R.string.suggestion_used, lookup.flight.text), announce)
                } else {
                    SuggestionCard(lookup, onUse = { leg ->
                        focus.clearFocus()
                        onUse(leg)
                    }, modifier = announce)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

/** Every suggested route for the flight number (the likelier airline first), then the caveat. */
@Composable
private fun SuggestionCard(found: FlightLookup.Found, onUse: (RouteLeg) -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.suggested_route),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            found.suggestions.forEachIndexed { i, suggestion ->
                if (i > 0) HorizontalDivider()
                Suggestion(found.flight.text, suggestion, onUse)
            }
            Text(
                stringResource(R.string.suggestion_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One airline's route. A direct flight: "BA117 usually flies", "LHR → JFK", and "Use this
 * route". A flight with stops: every stop, then a button per leg to pick from.
 */
@Composable
private fun Suggestion(flight: String, suggestion: RouteSuggestion, onUse: (RouteLeg) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.suggestion_airline, suggestion.airline.name, suggestion.callsign),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val direct = suggestion.stops.size == 2
        if (direct) {
            val leg = suggestion.legs.single()
            val names = stringResource(R.string.suggestion_route_names, leg.origin.name, leg.destination.name)
            Text(stringResource(R.string.suggestion_usually_flies, flight), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.flight_route, leg.origin.iata, leg.destination.iata),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { contentDescription = names },
            )
            // Read out once, by the line above.
            Text(names, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {})
            Button(onClick = { onUse(leg) }, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.use_this_route)) }
        } else {
            val stops = suggestion.stops.joinToString(" → ") { (code, airport) -> airport?.iata ?: code }
            Text(stringResource(R.string.suggestion_stops, flight, stops), style = MaterialTheme.typography.bodyMedium)
            for (leg in suggestion.legs) {
                val description = stringResource(R.string.use_leg_description, leg.origin.name, leg.destination.name)
                OutlinedButton(
                    onClick = { onUse(leg) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = description },
                ) { Text(stringResource(R.string.flight_route, leg.origin.iata, leg.destination.iata)) }
            }
        }
        if (suggestion.unknownStops.isNotEmpty()) {
            Text(
                stringResource(R.string.suggestion_unknown_stops, suggestion.unknownStops.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
