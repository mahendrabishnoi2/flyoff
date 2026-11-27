package io.github.mahendrabishnoi2.flyoff.location

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The location permission's current state and the actions the UI can offer. */
class LocationPermission(
    val access: LocationAccess,
    /** Shows the system permission dialog (or, if blocked, returns at once). Call only from a user action. */
    val request: () -> Unit,
    val openAppSettings: () -> Unit,
)

private val LOCATION_PERMISSIONS = arrayOf(
    // Both are requested together so Android 12+ lets the user choose approximate.
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Reads the location permission afresh on every resume (the user may have changed
 * it in settings) and after each request. The grant itself is never cached.
 */
@Composable
fun rememberLocationPermission(): LocationPermission {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var askedBefore by rememberSaveable { mutableStateOf(false) }
    var blockConfirmed by rememberSaveable { mutableStateOf(false) }
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }
    var readCount by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        askedBefore = true
        blockConfirmed = blockedAfterRequest(
            blockedBefore = blockConfirmed,
            rationaleBefore = rationaleBeforeRequest,
            fineGrantedAfter = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
            rationaleAfter = activity.fineRationale(),
        )
        readCount++
    }
    LifecycleResumeEffect(Unit) {
        readCount++
        onPauseOrDispose { }
    }

    val access = remember(readCount, askedBefore, blockConfirmed) {
        locationAccess(
            fineGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
            shouldShowRationale = activity.fineRationale(),
            askedBefore = askedBefore,
            blockConfirmed = blockConfirmed,
        )
    }
    return LocationPermission(
        access = access,
        request = {
            rationaleBeforeRequest = activity.fineRationale()
            launcher.launch(LOCATION_PERMISSIONS)
        },
        openAppSettings = { openAppSettings(context) },
    )
}

private fun Context.isGranted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Activity?.fineRationale(): Boolean =
    this?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ?: false

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivitySafely(intent)
}

/** Opens the system screen where location can be turned on. */
fun openLocationSettings(context: Context) {
    context.startActivitySafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some trimmed-down builds lack these settings screens; nothing more to offer.
    }
}
