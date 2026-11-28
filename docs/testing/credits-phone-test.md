# Credits screen: phone test

CI's emulator smoke test opens the credits screen from **More options**, checks it lists OpenStreetMap, OurAirports and VRS, opens OpenStreetMap's ODbL text, scrolls to the end, and checks the app used no network meanwhile. An emulator can't show a screen reader, a real browser hand-off, or how the screen reads on a real phone, so these steps need a real phone (Android 8 or later). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the branch's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Uninstall any earlier Flyoff first.

## Steps

1. **Offline.** Turn airplane mode on and open Flyoff. The map still shows "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors" (or the (i) button it collapses to), as before.
2. **Open it.** Tap the **⋮** button at the left of the buttons above the panel, then **Credits and licences**. Also try it with a flight set up: the ⋮ button is there too.
3. **What it lists.** Scroll through. The groups are Flyoff, Map, Airports and time zones, Flight routes, Fonts and Open-source libraries. Map lists OpenStreetMap (ODbL 1.0), OpenMapTiles (CC BY 4.0), OpenFreeMap (Terms of use) and Natural Earth (Public domain); then OurAirports, timezone-boundary-builder, VRS standing data, Noto Sans, and the libraries from MapLibre Native to Timber. The list ends with "Thank you to everyone who makes and shares these."
4. **Licence texts, offline.** Still in airplane mode, tap **Licence text** under OpenStreetMap: the ODbL opens, its paragraphs fitting the screen width. Scroll to its end. Press back: the list is where you left it. Try **Licence text** under MapLibre Native (the longest, with its components' licences) and under Flyoff (the GPL).
5. **Links.** Turn airplane mode off. Tap **openstreetmap.org/copyright**: the browser opens that page. Back in Flyoff, tap **Licence terms** under OpenFreeMap: openfreemap.org/tos opens. Nothing opens or loads until you tap.
6. **Rotate.** Rotate to landscape on the list and on a licence text: the text is readable and you stay where you were.
7. **Screen reader (TalkBack).** Turn TalkBack on and open the credits screen.
   - The ⋮ button reads "More options".
   - Swiping moves through the group titles as headings (headings navigation jumps between them), and each credit is read in one go: name, notice, what it's for, and its licence.
   - The buttons read "Read the ODbL 1.0 licence of OpenStreetMap" and "Open openstreetmap.org/copyright in your browser", not just "Licence text".
   - Turn TalkBack off.
8. **Back.** Press back (or the ×) on the list: the map comes back, with the flight, if any, as it was.

## Results

| Date | Phone / Android version | Steps passed | Notes |
|---|---|---|---|
| | | | |
