# Milestone 4: phone test

CI's emulator smoke test downloads the route map of a short route (LHR→CDG; another route can be picked when the workflow is started by hand), checks the logged tile and byte counts against OpenFreeMap's limits, compares the map at zoom 8 before and after, checks that moving the map outside the download uses no network, and checks that the map still shows in airplane mode after a restart. An emulator can't show a real phone's Wi-Fi and mobile data, how long a long download takes on a real connection, real storage use, or a real landing, so these checks need a real phone (Android 8 or later). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the PR's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Uninstall any earlier Flyoff first. Allow **precise** location when asked.

Each download goes to OpenFreeMap, a free service run by one person. Please don't download the same route over and over: one London–New York download for step 3 and a short route for the rest is plenty.

## Steps

1. **Nothing downloads by itself.** Install the app, open it and set up a short flight, for example LHR → CDG.
   - Before any tap, the flight card shows "Route map: up to N MB, one download. Wi-Fi only.", the **Stored maps** button (a stack of layers) and a **Download route map (up to N MB)** button. Nothing is downloading.
   - Write down the app's data use: Settings → Apps → Flyoff → Mobile data & Wi-Fi (or Data usage). It should be 0 B so far.
2. **Wi-Fi only.** Turn Wi-Fi off and mobile data on, then tap **Download route map**.
   - The card says "Not on Wi-Fi. Connect to Wi-Fi, or allow mobile data…", and nothing downloads.
   - Tap **Stored maps** (the layers icon), turn on **Use mobile data for route maps**, go back and tap the button again: the download starts on mobile data. Tap **Cancel**: it stops. Stored maps lists no finished part for the flight (at most "Route map · 0 of 1 part", a piece that finished before the cancel); delete it.
   - Turn **Use mobile data for route maps** off again and Wi-Fi on.
3. **London–New York (the milestone's "done when").** On Wi-Fi, set up LHR → JFK.
   - Before any tap the card shows the total size and the number of downloads. Most of the route is ocean, so it is one download today ("Route map: up to 34 MB, one download."); if it says several, the button reads **Part 1 of N · up to N MB**.
   - Tap it. The card shows "Downloading route map" (or "· part 1 of N"), a progress bar, "N % · N MB of up to N MB", "Keep Flyoff open until it finishes." and **Cancel**. Write down how long it takes.
   - When it finishes the card shows **Ready for offline ✓**, and offers the detailed map (zoom 9) with its size.
   - Optional, if a computer with `adb` is at hand: `adb logcat -s Flyoff` shows a `download-done` line per download with `tiles=` at most 3000 and `bytes=` at most 50000000.
   - **Several downloads:** set up a route over more land, for example FRA → DEL. The card says "Route map: up to N MB in N downloads, one tap each." and shows **Part 1 of N**. Download part 1 only: the card then says "Route map: 1 of N parts downloaded." and shows **Part 2 of N**. (You can delete it in step 7 instead of downloading every part.)
4. **Airplane mode.** Turn airplane mode on, swipe Flyoff away from Recents and open it again.
   - The card still shows **Ready for offline ✓**.
   - Zoom in near London: within the route's strip the map shows roads, built-up areas and woods that the world map alone doesn't have, down to zoom 8. Pan along the route over Ireland and the Atlantic: the extra detail follows the route. Away from the route (for example over Spain) it is the plain world map.
   - The map credit "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors" (or its (i) button) is still on the map.
5. **Interrupted download.** Turn airplane mode off. Set up a different route (for example LHR → AMS), tap its download and, while it runs, swipe Flyoff away from Recents. Open Flyoff again.
   - The card says "The last download stopped before it finished (Flyoff was closed). Download it again to continue where it stopped." Tap the button again: it finishes, and is quicker than a fresh download.
6. **Detailed map.** On Wi-Fi, with LHR → CDG ready, tap **Download detailed map**.
   - It downloads with progress like the route map, then the card says "Detailed map downloaded." Near Paris you can now zoom one level further (zoom 9), and see more roads.
   - With Wi-Fi off and mobile data allowed in Stored maps, the detailed map still says "Not on Wi-Fi".
7. **Stored maps.** Tap **Stored maps** (the layers icon next to the badge).
   - Each flight is listed with its route ("LHR → JFK"), "Route map · 1 of 1 part", the size and the date, "Kept until you delete it.", and **Delete**. The total at the top adds them up.
   - Compare the total with Settings → Apps → Flyoff → Storage: the app's data should be about that much more than before the downloads.
   - Delete one flight and confirm: it goes away, the total drops, and its flight card offers the download again.
8. **Deleted 3 days after landing.** On a real flight (or a car or train trip to a place you set as the destination), download the route map, then track the trip to within 30 km of the destination.
   - Stored maps says "Landed; deleted on <date>", 3 days later.
   - Open Flyoff after that date: the flight's maps are gone from Stored maps.
9. **No network while tracking.** With a route map downloaded, airplane mode off and Wi-Fi on, note the app's data use (step 1), start tracking, pan and zoom around for a few minutes inside and outside the route's strip, and stop tracking.
   - The app's data use hasn't changed.

## Results

| Date | Phone / Android version | Steps passed | LHR→JFK: parts, MB, minutes (step 3) | Notes |
|---|---|---|---|---|
| | | | | |
