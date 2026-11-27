package io.github.mahendrabishnoi2.flyoff.location

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Satellite counts from [GnssStatus], for the "GPS ready" check: listened to only while [active]
 * and the app is visible (started), never in the background. It doesn't turn the GPS on by
 * itself; it reports on whoever has (the screen or the tracking service). Null until the first
 * status, and when not listening.
 */
@Composable
fun rememberGnssSatellites(active: Boolean): Satellites? {
    val context = LocalContext.current
    var satellites by remember { mutableStateOf<Satellites?>(null) }
    LifecycleStartEffect(active, context) {
        satellites = null
        val locationManager = context.getSystemService(LocationManager::class.java)
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
                // An equal value doesn't recompose anything.
                satellites = Satellites(inView = status.satelliteCount, usedInFix = used)
            }

            override fun onStopped() {
                satellites = null
            }
        }
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val registered = active && granted && try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            }
        } catch (_: SecurityException) {
            false
        }
        onStopOrDispose {
            if (registered) locationManager.unregisterGnssStatusCallback(callback)
        }
    }
    return satellites
}
