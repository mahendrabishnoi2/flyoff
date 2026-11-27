# Milestone 3: phone test

CI's emulator smoke test starts tracking, turns the screen off while it feeds fake fixes, swaps the GPS for a silent test provider to check the estimate (and its grey dot) during a drop-out, resumes tracking after a force-stop, runs the simulator with GPS gaps and shows the Battery Saver warning. An emulator can't show what a real GPS receiver, a sleeping phone, a phone maker's battery rules or a real battery do, so these checks need a real phone (Android 8 or later, with GPS). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the PR's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Uninstall any earlier Flyoff first. Allow **precise** location when asked.

## Steps

1. **GPS ready.** Set up a flight from an airport near you (for example your nearest airport to the next one). Go outdoors (or near a window) in airplane mode, with Wi-Fi off.
   - The flight card shows "Searching for GPS…", then "Searching for GPS · N satellites in view" as satellites appear.
   - Once there is a fix it says "GPS ready · ±N m · N satellites". Indoors, a rough fix may say "Weak GPS signal · ±N m" instead.
   - Press Home for a minute and come back: the line starts searching again only briefly.
2. **Start tracking and the notification.** Tap **Start tracking**.
   - On Android 13 or later the app asks to send notifications. Allow it. (Step 9 covers refusing.)
   - The card says "Recording track · N points" with **Stop tracking**, and **Simulate flight** is gone.
   - A "Tracking <from> → <to>" notification appears with the plane icon, without a sound or vibration. Within 15 s of a fix its text reads like "123 km to go" (and "· ETA 14:05 CET" once you move faster than 50 km/h).
   - Swipe the notification away. Up to Android 13 it can't be swiped away. On Android 14 and later Android lets you dismiss it, but tracking keeps running and Flyoff posts it again within 15 s (it refreshes the notification every 15 s): check that it comes back and that the point count keeps growing.
   - Tapping the notification opens Flyoff. Its **Stop tracking** action stops tracking, and the notification goes away. Start tracking again.
3. **Screen off on a real trip (the milestone's "done when").** On a car, bus or train journey of at least 30 minutes (better, a flight), start tracking with the phone at full charge, and write down the battery % and the time. Turn the screen off and put the phone in a pocket or bag; for a flight, turn on airplane mode before take-off as usual.
   - At the end, turn the screen on and open Flyoff: the blue flown track follows your whole route on the map, with no long straight jumps except where you lost the sky (tunnels, deep in a building).
   - The point count went up by about 12 a minute (one point per 5 s with the screen off).
   - Write down the battery % and the time again, and work out % per hour. Note it in the results table. Expect a few % an hour; much more than 10 % an hour is worth reporting.
   - Stop tracking.
4. **GPS drop-out.** While tracking at speed (a train or a car as a passenger), go through a tunnel or underground, or hold the phone deep inside a bag for a minute, with Flyoff on screen.
   - About 10 s after the last fix the dot turns from blue to grey (a blue-grey, #a1b0c0) and keeps moving towards the destination at the last speed, and the card says "Estimated position · last GPS fix < 1 min ago" (then "1 min ago", …). The stats come from the estimate.
   - The notification says "Estimated position · last GPS fix … ago" (it updates every 15 s).
   - When the GPS comes back the dot turns blue again and jumps to the real position, and the line goes back to "GPS ready".
5. **Location turned off.** While tracking, turn location off in quick settings.
   - Tracking keeps running. The app shows "Location is off" and the notification "Location is off · turn it on to keep recording".
   - Turn location on again: fixes and points resume without tapping anything.
6. **Battery Saver.** Turn Battery Saver on (quick settings, or Settings → Battery).
   - If the phone's Battery Saver turns GPS off with the screen off, Flyoff shows "Battery Saver is on — Battery Saver may turn GPS off when the screen is off…" with a **Battery Saver settings** button, which opens the right settings screen (or the main settings).
   - Turn Battery Saver off: the warning goes away when you return to the app.
   - Stock Android (Pixel) uses a mode that keeps GPS for foreground services, so there may be no warning there: note what you see. **On a Samsung or Xiaomi phone**, if you have one, repeat this and also the screen-off test (step 3) for 10 minutes with Battery Saver *on*, and note whether the track has a gap.
7. **Killing the app while tracking.** While tracking, swipe Flyoff away from Recents.
   - On most phones the notification stays and tracking continues; check the point count after a few minutes. Some phones (Xiaomi, Huawei, some Samsung settings) stop it; note which.
   - Then force-stop it: Settings → Apps → Flyoff → Force stop (to Flyoff this is the same as Android ending it for memory). The notification disappears. Open Flyoff: a "Tracking stopped" card says "Android closed Flyoff while it was recording this flight…" with **Resume** and **Dismiss**, and the track recorded so far is still drawn.
   - Tap **Resume**: tracking starts again, the point count carries on from where it was, and the new points join the old track.
   - Force-stop again, open Flyoff and tap **Dismiss**: the card goes away and doesn't come back on the next launch. Start tracking again.
8. **Changing the flight.** While tracking, tap **Change**, pick a different destination and tap **Done**.
   - Tracking stops, the notification goes away, and the old track disappears. A new **Start tracking** starts a fresh track at 0 points.
9. **Notifications refused (Android 13+).** Clear Flyoff's notification permission (Settings → Apps → Flyoff → Notifications off), then start tracking and refuse notifications if asked.
   - Tracking still starts. The card says "Notifications are off, so tracking shows only in Android's list of active apps".
   - With the screen off for 5 minutes the track still grows. Pull down quick settings: Flyoff is listed under active apps (the "N apps are active" button), where it can be stopped.
   - Turn notifications back on.
10. **Simulator with GPS gaps.** Stop tracking. Tap **Simulate flight**, turn on **GPS gaps**, choose **300×** and tap **Start**.
    - At about 30 % of the way (after roughly 23 s on LHR→JFK) the dot turns from blue to grey and the card says "Estimated position · last GPS fix … ago", counting minutes of simulated time; the estimate keeps moving. About 8 s later real simulated fixes resume. A second, shorter gap comes at about 65 %.
    - With **GPS gaps** off there is never an estimate.
    - **Start tracking** isn't offered while the simulator runs.

## Results

| Date | Phone / Android version | Steps passed | Battery % per hour (step 3) | Notes |
|---|---|---|---|---|
| | | | | |
