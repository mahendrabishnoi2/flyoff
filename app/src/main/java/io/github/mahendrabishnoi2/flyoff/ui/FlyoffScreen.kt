package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.location.GpsState
import io.github.mahendrabishnoi2.flyoff.location.LocationAccess
import io.github.mahendrabishnoi2.flyoff.location.LocationPermission
import io.github.mahendrabishnoi2.flyoff.location.openLocationSettings
import io.github.mahendrabishnoi2.flyoff.location.rememberGpsUpdates
import io.github.mahendrabishnoi2.flyoff.location.rememberLocationPermission
import io.github.mahendrabishnoi2.flyoff.location.summary
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import io.github.mahendrabishnoi2.flyoff.map.MapInsets
import io.github.mahendrabishnoi2.flyoff.map.MapLibreMap

/** The one screen: the offline map, with a panel at the bottom for location status. */
@Composable
fun FlyoffScreen() {
    val permission = rememberLocationPermission()
    val precise = permission.access == LocationAccess.PRECISE
    val gps = rememberGpsUpdates(active = precise)
    var following by rememberSaveable { mutableStateOf(false) }

    // Keep MapLibre's attribution, logo and compass clear of the system bars and of the panel.
    var panelHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val ornamentInsets = MapInsets(
        left = safeDrawing.getLeft(density, LayoutDirection.Ltr),
        top = safeDrawing.getTop(density),
        right = safeDrawing.getRight(density, LayoutDirection.Ltr),
        bottom = panelHeight,
    )

    Box(Modifier.fillMaxSize()) {
        MapLibreMap(
            location = gps.location,
            showLocation = precise,
            following = following && precise,
            onFollowingChange = { following = it },
            ornamentInsets = ornamentInsets,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { panelHeight = it.height }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            StatusPanel(
                permission = permission,
                gps = gps,
                following = following,
                onFollowingChange = { following = it },
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

@Composable
private fun StatusPanel(
    permission: LocationPermission,
    gps: GpsState,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        val location = gps.location
        if (permission.access == LocationAccess.PRECISE && location != null) {
            ElevatedFilterChip(
                selected = following,
                onClick = { onFollowingChange(!following) },
                label = { Text(stringResource(R.string.follow_me)) },
            )
        }
        when (permission.access) {
            LocationAccess.PRECISE -> when {
                !gps.providerEnabled -> MessageCard(
                    title = stringResource(R.string.gps_off_title),
                    body = stringResource(R.string.gps_off_body),
                    action = stringResource(R.string.open_location_settings),
                    onAction = { openLocationSettings(context) },
                )
                location == null -> MessageCard(
                    title = stringResource(R.string.gps_waiting_title),
                    body = stringResource(R.string.gps_waiting_body),
                )
                else -> {
                    val locale = LocalConfiguration.current.locales[0]
                    MessageCard(
                        title = stringResource(R.string.gps_fix_title),
                        body = location.toGpsFix().summary(locale),
                    )
                }
            }
            LocationAccess.NOT_ASKED, LocationAccess.DENIED -> MessageCard(
                title = stringResource(R.string.permission_title),
                body = stringResource(R.string.permission_body),
                action = stringResource(R.string.permission_allow),
                onAction = permission.request,
            )
            LocationAccess.BLOCKED -> MessageCard(
                title = stringResource(R.string.permission_blocked_title),
                body = stringResource(R.string.permission_blocked_body),
                action = stringResource(R.string.open_app_settings),
                onAction = permission.openAppSettings,
                secondaryAction = stringResource(R.string.ask_again),
                onSecondaryAction = permission.request,
            )
            LocationAccess.APPROXIMATE -> MessageCard(
                title = stringResource(R.string.approximate_title),
                body = stringResource(R.string.approximate_body),
                action = stringResource(R.string.approximate_allow),
                onAction = permission.request,
            )
            LocationAccess.APPROXIMATE_BLOCKED -> MessageCard(
                title = stringResource(R.string.approximate_title),
                body = stringResource(R.string.approximate_body),
                action = stringResource(R.string.open_app_settings),
                onAction = permission.openAppSettings,
                secondaryAction = stringResource(R.string.ask_again),
                onSecondaryAction = permission.request,
            )
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (secondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
                    }
                    Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
