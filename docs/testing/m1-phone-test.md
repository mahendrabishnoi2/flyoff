# Milestone 1: phone test

These checks can't run in a cloud session (no emulator), so they're done by hand on a real Android phone (Android 8 or later, with GPS). Note the phone model and Android version, and the result of each step.

**Build and install**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Steps

1. **Offline map.** Turn on airplane mode and turn Wi-Fi off. Open Flyoff.
   - The world map renders.
   - The map credit is visible and not hidden under the status or navigation bar.
   - Status-bar and navigation-bar icons are dark and readable, in both light and dark system themes.
2. **Zoom.** Zoom in over a populated area to zoom 6–8.
   - The map stays drawn (it doesn't go blank).
   - Smaller city names appear from zoom 5–6.
3. **Approximate location.** Tap "Allow location" and choose **Approximate**.
   - You see "Precise location needed".
   - "Use precise location" opens the upgrade dialog.
4. **Dismissed dialog.** Clear the app's permissions, tap "Allow location", then tap outside the dialog.
   - You see the "denied" card with both "Allow location" and "Open settings".
5. **Blocked.** Deny twice, or deny with "Don't ask again".
   - You see "Location permission is off".
   - "Open settings" opens Flyoff's settings page.
6. **First fix.** Grant **Precise** location.
   - You see "Waiting for GPS…".
   - Go outdoors and time the first fix (expect up to several minutes in airplane mode).
7. **Moving.** Walk or drive.
   - The dot follows you; speed and GPS altitude update.
   - At walking pace the arrow doesn't flicker between arrow and dot.
8. **Follow me.** Tap "Follow me".
   - The map zooms in and tracks you.
   - Panning the map turns "Follow me" off.
9. **Lost fix.** Cover the phone or go indoors until GPS drops, or toggle location off and on.
   - The dot disappears rather than staying at the old position.
   - Location off shows the "Location is off" card.
10. **Rotation.** Rotate the screen.
    - The camera, follow state and dot stay as they were.
11. **Background.** Background the app for a minute, then check Settings → Location → recent access.
    - Flyoff isn't using location.
    - When you return, "Waiting…" shows briefly, then the fix comes back.
12. **Release build (optional).** Install the release APK and repeat step 1. This checks that the minified build starts.

## Results

| Date | Phone / Android version | Steps passed | Notes |
|---|---|---|---|
| | | | |
