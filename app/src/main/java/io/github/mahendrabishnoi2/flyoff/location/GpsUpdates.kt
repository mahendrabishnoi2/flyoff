package io.github.mahendrabishnoi2.flyoff.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/** The GPS receiver's state as the UI needs it. */
data class GpsState(
    /** False on a device without a GPS receiver; nothing else here matters then. */
    val hasReceiver: Boolean,
    /** False when the user has turned location off in system settings. */
    val providerEnabled: Boolean,
    /** The latest fix since the app came to the foreground, or null while waiting for one. */
    val location: Location?,
)

private const val UPDATE_INTERVAL_MS = 1_000L

/**
 * Listens to the satellite GPS receiver ([LocationManager.GPS_PROVIDER]) while the
 * app is in the foreground and [active] is true. It uses no network and no Google
 * Play services, so it works in airplane mode. Updates stop when the app goes to
 * the background (no background tracking in Milestone 1).
 */
@Composable
fun rememberGpsUpdates(active: Boolean): GpsState {
    val context = LocalContext.current
    val hasReceiver = remember(context) { hasGpsReceiver(context) }
    var state by remember { mutableStateOf(GpsState(hasReceiver, providerEnabled = true, location = null)) }

    LifecycleStartEffect(active, context) {
        val locationManager = context.getSystemService(LocationManager::class.java)
        // Every method is overridden: before Android 11 the platform interface has
        // no default methods, and a missing onStatusChanged crashes there.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                state = state.copy(providerEnabled = true, location = location)
            }

            override fun onProviderEnabled(provider: String) {
                state = state.copy(providerEnabled = true)
            }

            override fun onProviderDisabled(provider: String) {
                state = state.copy(providerEnabled = false, location = null)
            }

            @Deprecated("Deprecated in Android 10; still called on older versions.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        // A fix from before the app went to the background may be far out of date.
        state = GpsState(hasReceiver, providerEnabled = true, location = null)
        if (active && hasReceiver) {
            startGps(context, locationManager, listener) { enabled -> state = state.copy(providerEnabled = enabled) }
        }
        onStopOrDispose { locationManager.removeUpdates(listener) }
    }
    return state
}

private fun startGps(
    context: Context,
    locationManager: LocationManager,
    listener: LocationListener,
    onProviderEnabled: (Boolean) -> Unit,
) {
    val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return
    onProviderEnabled(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    try {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
        )
    } catch (_: SecurityException) {
        // The permission was revoked between the check and the call. The next
        // resume reads the permission again and the UI asks for it.
    } catch (_: IllegalArgumentException) {
        // No GPS provider after all; hasGpsReceiver() should have caught this.
        onProviderEnabled(false)
    }
}

/** True if the device has satellite GPS and the platform offers its provider. */
private fun hasGpsReceiver(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) &&
        LocationManager.GPS_PROVIDER in context.getSystemService(LocationManager::class.java).allProviders

/** The fields of [Location] that the status line shows. */
fun Location.toGpsFix() = GpsFix(
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    hasBearing = hasBearing(),
)
