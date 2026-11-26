package io.github.mahendrabishnoi2.flyoff.location

/** What the app may do with location right now, and what the UI should offer. */
enum class LocationAccess {
    /** Precise location granted: the GPS dot can be shown. */
    PRECISE,

    /** Only approximate location; asking for precise may still show the system dialog. */
    APPROXIMATE,

    /** Only approximate location, and the user has refused precise for good: send them to settings. */
    APPROXIMATE_BLOCKED,

    /** Never asked in this session, and no grant yet. */
    NOT_ASKED,

    /**
     * Not granted, and it isn't certain the system will refuse to ask: the user denied
     * once, dismissed the dialog, or blocked it in an earlier session. Offer both
     * "ask again" and app settings.
     */
    DENIED,

    /** The user answered "don't ask again" (or denied twice): only app settings can change it. */
    BLOCKED,
}

/**
 * Maps the platform's permission state to a [LocationAccess].
 *
 * `shouldShowRationale` is `shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)`.
 * It is false before the first request, after "don't ask again", and also after
 * the user dismisses the dialog on Android 11+, so false alone never means
 * blocked. [blockConfirmed] comes from [blockedAfterRequest].
 */
fun locationAccess(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    shouldShowRationale: Boolean,
    askedBefore: Boolean,
    blockConfirmed: Boolean,
): LocationAccess {
    val blocked = blockConfirmed && !shouldShowRationale
    return when {
        fineGranted -> LocationAccess.PRECISE
        coarseGranted -> if (blocked) LocationAccess.APPROXIMATE_BLOCKED else LocationAccess.APPROXIMATE
        shouldShowRationale -> LocationAccess.DENIED
        blocked -> LocationAccess.BLOCKED
        askedBefore -> LocationAccess.DENIED
        else -> LocationAccess.NOT_ASKED
    }
}

/**
 * Whether precise location is known to be blocked after a request returns.
 *
 * Only one sequence proves it: the rationale flag was true before the request (so
 * the system showed its dialog) and false after it, with precise still not granted;
 * that is the user answering "deny" the second time. A dismissed dialog leaves the
 * flag unchanged, and a request that returns at once without a dialog starts with
 * it false, so neither counts.
 */
fun blockedAfterRequest(
    blockedBefore: Boolean,
    rationaleBefore: Boolean,
    fineGrantedAfter: Boolean,
    rationaleAfter: Boolean,
): Boolean = !fineGrantedAfter && !rationaleAfter && (rationaleBefore || blockedBefore)
