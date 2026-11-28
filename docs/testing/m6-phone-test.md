# Milestone 6 (flight log): phone test

CI's emulator smoke test stops a tracked flight and checks its summary, runs a simulated flight to touchdown and checks the summary's numbers, exports it as GPX and KML through Android's file picker and checks the files, feeds fake fixes until tracking ends on its own at the destination, then checks that History lists the three flights newest first and that deleting them leaves the empty state. An emulator can't show a real GPS receiver on a real trip with the screen off, real altitudes and speeds, or another app opening the exported file, so these checks need a real phone (Android 8 or later). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the PR's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Install it **over** the build you used for the [Milestone 3 phone test](m3-phone-test.md) if you still have it (step 2 needs that), otherwise uninstall any earlier Flyoff first and skip step 2.

You also need an app that opens GPX or KML files, such as OsmAnd, Organic Maps or Google Earth, and a short trip by car, bus or train (20 minutes or more) between two places near airports, or the airport of your next flight.

## Steps

1. **Empty history.** Turn airplane mode on. Open Flyoff. The panel's top row has **History** before Follow me. Tap it.
   - "Flight history" with "No flights yet" and a line saying tracked flights are kept here, and simulated ones too once they touch down.
   - Press Back: the map is back.
2. **Upgrade with a flight in progress.** Only if you installed over a Milestone 3–5 build: in that build, set up a flight, tap **Start tracking**, wait a minute, then install this build over it without stopping.
   - Open Flyoff: tracking carries on (or offers **Resume**). Tap **Stop tracking**: the summary of that flight opens, with its track from before the upgrade. It is in History.
3. **A simulated flight.** Set up LHR → CDG, tap **Simulate flight**, pick **300 times real time** and tap **Start**.
   - When it touches down (about 8 s), "Flight summary" opens: "LHR → CDG", the date and times, "Simulated flight", the route and the track on the map, Distance flown about the same as Great-circle distance (about 347 km), Duration about 39 min, Max altitude in thousands of metres, Max ground speed and Average speed in km/h (average below max), "Estimated position: 0 min".
   - Press Back, then **Stop** the replay. Start another replay and tap **Stop** halfway: that one is **not** kept (History has only the one simulated flight).
4. **A real trip with the screen off.** Set up a flight whose origin is near where you start the trip and whose destination is near where it ends (within 30 km), or your real flight. Wait for "GPS ready", tap **Start tracking**, turn the screen off and put the phone away for the whole trip.
   - At the end, if you were moving faster than 50 km/h and stopped within 30 km of the destination, tracking ends on its own: the notification goes away and, when you turn the screen on and open Flyoff, the summary is open. Otherwise tap **Stop tracking**: the summary opens.
   - Check the summary against the trip: the track on the map follows where you went (it is drawn even in airplane mode), Distance flown is about the trip's length (more than the great-circle distance if the route bent), Duration matches, Max altitude and Max ground speed look right for the trip (GPS heights, so a few tens of metres off), and Estimated position is the time GPS was lost (in tunnels, for example).
5. **History.** Tap **History**. The flights are listed newest first, each with its route, date, duration and distance; the simulated one says "Simulated flight". Tap one: its summary opens. Back returns to History, Back again to the map. Rotate to landscape and back on each screen: nothing is lost.
6. **Export GPX and open it in another app.** Open the real trip's summary and tap **Export GPX**. Android's file picker opens with the name `flyoff-<date>-<from>-<to>.gpx` (in Downloads, or the last folder used). Tap **Save**.
   - The summary says "Saved flyoff-….gpx". Nothing was sent anywhere (airplane mode is still on).
   - Open the file in OsmAnd, Organic Maps or another GPX app (from the Files app, or that app's import): the track follows your trip, the two airports are waypoints, and the app shows times (and heights, if it shows them) along the track.
7. **Export KML.** Tap **Export KML**, save it, and open it in Google Earth (or another KML app): the track and the two airports appear, and Google Earth's time slider plays the trip.
   - In the file picker, tap Back instead of Save: nothing happens and no message appears.
8. **The simulated flight's file.** Export the simulated flight as GPX: its name ends in `-simulated.gpx` and the other app shows "Simulated flight" as the track's type or description.
9. **Delete.** In a summary tap **Delete**: "Delete the flight …?" says exported files stay. Tap **Cancel**: nothing changes. Tap **Delete**, then **Delete flight**: History no longer lists it. Delete them all: "No flights yet". The exported files are still in Downloads.
10. **Kept across restarts, not in backups.** Track a short flight again and stop it. Swipe Flyoff away from Recents and restart the phone: the flight is still in History. (Settings → Google → Backup doesn't hold the flight log: it is kept out of cloud backup on purpose.)
11. **Screen reader (TalkBack).** With TalkBack on, open a summary: the route is read with the airport names, each figure as "Distance flown: 347 km" and so on, and the export and delete buttons by name. Turn TalkBack off.

## Results

| Date | Phone / Android version | Steps passed | Trip (from, to, minutes) | Notes |
|---|---|---|---|---|
| | | | | |
