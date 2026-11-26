package io.github.mahendrabishnoi2.flyoff.location

/** What the app may do with location right now, and what the UI should offer. */
enum class LocationAccess {
    /** Precise location granted: the GPS dot can be shown. */
    PRECISE,

    /** Only approximate location; asking for precise may still show the system dialog. */
    APPROXIMATE,

    /** Only approximate location, and the system won't ask again: send the user to settings. */
    APPROXIMATE_BLOCKED,

    /** Never asked in this session, and no grant yet. */
    NOT_ASKED,

    /** Denied, but the system will still show its dialog: explain, then ask again. */
    DENIED,

    /** Denied with "don't ask again" (or denied twice): only app settings can change it. */
    BLOCKED,
}

/**
 * Maps the platform's permission state to a [LocationAccess].
 *
 * `shouldShowRationale` is `shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)`.
 * It is false both before the first request and after "don't ask again", so
 * `askedBefore` (a request finished in this session) tells the two apart. After a
 * fresh start a blocked permission looks like [NOT_ASKED]; the next request then
 * returns at once without a dialog and the state becomes blocked.
 */
fun locationAccess(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    shouldShowRationale: Boolean,
    askedBefore: Boolean,
): LocationAccess {
    val systemWillAsk = shouldShowRationale || !askedBefore
    return when {
        fineGranted -> LocationAccess.PRECISE
        coarseGranted -> if (systemWillAsk) LocationAccess.APPROXIMATE else LocationAccess.APPROXIMATE_BLOCKED
        shouldShowRationale -> LocationAccess.DENIED
        askedBefore -> LocationAccess.BLOCKED
        else -> LocationAccess.NOT_ASKED
    }
}
