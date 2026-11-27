# Milestone 2: phone test

CI's emulator smoke test sets up LHR→JFK, runs the simulator and checks that the stats move, but these checks need a real phone (Android 8 or later, with GPS). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the PR's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Uninstall any earlier Flyoff first.

## Steps

1. **Offline flight setup.** Turn on airplane mode and turn Wi-Fi off. Open Flyoff and tap **Set up flight**.
   - The search field is focused and the keyboard is up.
   - Search by city ("london"), airport name ("heathrow"), IATA code ("lhr") and ICAO code ("egll"): each finds London Heathrow. "zurich" finds Zürich; "new york" lists JFK and LGA first.
   - Each result shows the IATA code large, the airport name, and the city and country.
   - Pick **LHR** for From: the search moves on to To. Pick **JFK**, then tap **Done**.
2. **Same airport.** Open **Change**, tap **To**, and pick **LHR** again.
   - A message says origin and destination must be different airports, and To stays JFK.
   - The swap button exchanges From and To. Close with the cross (or Back) without tapping Done: the flight is still LHR→JFK.
3. **Route.** Back on the map:
   - A magenta great-circle line runs from LHR to JFK, curving north towards Greenland, with a dot and "LHR" / "JFK" at its ends.
   - Ocean, country and city names stay readable on top of the line.
   - The whole route is in view above the panel, not under it.
   - The panel says "LHR → JFK" and "5,540 km route".
4. **Simulator.** Tap **Simulate flight**.
   - A "Simulated position, not your real one" badge shows at the top, and a "Simulated flight" strip in the panel.
   - Before **Start** the dot waits at Heathrow: speed "0 km/h", progress "0%", and no ETA ("—").
   - Choose **300×** and tap **Start**. The dot (an arrow) leaves Heathrow and moves along the line; the whole flight takes about 75 seconds.
   - The stats are plausible: altitude climbs to about 11,000 m and speed to about 900 km/h; heading starts around 290° (west-north-west) and ends around 230° (south-west); remaining goes down and flown goes up; progress rises; time left shrinks; the ETA (New York time, "EST" or "EDT") stays about the same.
   - **Follow me** tracks the moving dot. Pausing stops the dot; Resume carries on.
   - Progress only reads "100%" at JFK, never while there is distance left.
   - At JFK the replay stops with remaining "0.0 km", speed "0 km/h", and the button says **Replay**.
   - **Stop** removes the badge and the strip, and the panel goes back to real GPS.
5. **Rotation and restart while simulating.** Start a replay at **10×**. Rotate the phone, then rotate back.
   - The replay carries on from where it was (it doesn't restart or stop), and the badge stays.
   - In landscape the panel sits on the left and the map is visible beside it. The badge, the MapLibre logo and the (i) attribution button are on the map to the right of the panel, nothing covers them, and the (i) button opens the attribution.
   - Press Home, wait 30 seconds, and come back: the dot has moved on by about 5 minutes of flight.
6. **Process death.** With a flight set, enable **Developer options → Don't keep activities** (or run `adb shell am kill io.github.mahendrabishnoi2.flyoff` while the app is in the background). Leave and return to the app.
   - The flight is still set and the route drawn. A replay that was running is still running, with the badge, and "Set up flight" doesn't flash up while the app starts.
   - Swipe the app away from Recents and open it again: the flight is still set (a replay is not kept).
   - Turn Don't keep activities off again.
7. **Without location permission.** In system settings, remove Flyoff's location permission. Open the app.
   - The flight is still set. **Simulate flight** works: the dot, badge and stats all show. Stop it.
   - Grant precise location again.
7a. **Large text.** In system settings set the font size (and display size) to the largest. Open the app with a flight set and simulate it.
   - The stats use fewer columns; nothing overlaps or is cut off mid-letter (long values end in "…"). The simulator buttons still fit. Set the sizes back.
8. **Real GPS stats on a drive or walk.** Set up a flight between two airports near you (for example two airports of your city, or your nearest airport to the next one). Go outdoors in airplane mode and walk or drive.
   - Before the first fix the panel shows the flight with its route length, and "Waiting for GPS…".
   - Once there is a fix the stats appear. Altitude, speed and heading come from the GPS; anything the GPS doesn't report shows "—", never a made-up 0.
   - Remaining goes down as you head towards the destination; heading matches your direction of travel (N, NE, E…).
   - Below 50 km/h time left and ETA show "—"; in a car at speed they appear.
9. **Time zones.** Set up a flight to a destination in another time zone (for example DEL, Delhi, or NRT, Tokyo) and simulate it.
   - The ETA shows the destination's local time with its zone name ("IST", "JST" or a GMT offset), and "+1" when it arrives the next day there.
   - On an old phone (Android 8–10), set up a flight to **CJS** (Ciudad Juárez, zone America/Ciudad_Juarez, added in 2022) or **BBA** (Balmaceda, America/Coyhaique, added in 2025). If the phone's time-zone data is too old, the stat says **ETA (UTC)** and the time ends in "UTC" instead of crashing.
10. **Antimeridian.** Set up **SYD → LAX**.
    - The route is one line across the Pacific, not a line back across Africa and the Atlantic, and the camera shows all of it.
11. **Clear.** Open **Change → Clear flight**.
    - The route and the flight card disappear, and the panel shows **Set up flight** again. After restarting the app no flight is set.

## Results

| Date | Phone / Android version | Steps passed | Notes |
|---|---|---|---|
| | | | |
