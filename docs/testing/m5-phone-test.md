# Milestone 5: phone test

CI's emulator smoke test types BA117 in flight setup and checks the suggestion (LHR → JFK) and "Use this route", picks the Singapore–Sydney leg of BA15, types an unknown flight (ZZ9999) and checks the airport picker still works, and taps "Update flight data". An emulator can't show a real keyboard on a real phone, a screen reader, a real Wi-Fi or mobile data connection, or the update downloading from the published release (which doesn't exist until the maintainer runs the "Flight data" workflow on `main`), so these checks need a real phone (Android 8 or later). Note the phone model and Android version, and the result of each step.

**Get the app** the same way as for the [Milestone 1 phone test](m1-phone-test.md): the `flyoff-<commit>-arm64-v8a.apk` from the PR's CI run, or `./gradlew assembleRelease -Pflyoff.testSigning`. Uninstall any earlier Flyoff first.

The routes are crowd-sourced (VRS standing data). The expected routes below are what that data says, not airline schedules.

## Steps

1. **Works offline, with nothing downloaded.** Turn airplane mode on. Open Flyoff and tap **Set up flight**.
   - Above From and To there is a **Flight number (optional)** field, and under it "Flight routes from 27 Nov 2026." (the bundled data's date, in your date format) with **Update flight data**.
   - The airport search field still has the focus and the keyboard, as before.
2. **BA117.** Tap the flight number field and type `ba 117`.
   - A card titled "Suggested route" says "British Airways · BAW117", "BA117 usually flies", "LHR → JFK", "London Heathrow Airport to John F Kennedy International Airport", a **Use this route** button, and "Routes are crowd-sourced and may be wrong or out of date. Check them against your booking."
   - Try `BA0117` and `BAW117`: the same suggestion.
   - Tap **Use this route**: the keyboard closes, From is LHR and To is JFK, and the card becomes one line, "Filled in from BA117's usual route. Check the airports, then tap Done."
   - Tap **Done**: the flight card shows LHR → JFK, as if the airports had been picked by hand.
3. **A flight with a stop (pick the leg).** Tap **Change**, then type `BA15` in the flight number field.
   - The card says "BA15 usually flies LHR → SIN → SYD. Which leg is yours?" with two buttons, **LHR → SIN** and **SIN → SYD**, and no "Use this route".
   - Tap **SIN → SYD**: From is SIN and To is SYD. Tap **Done**: the flight card shows SIN → SYD.
4. **Two airlines sharing a code.** Tap **Change** and type `QF1`.
   - The card lists Qantas (QFA1, with its legs via Singapore) first. If another airline with the code QF has a QF1 between airports Flyoff knows, it is listed below it, separated by a line.
5. **Unknown flight, and the fallback.** Tap the **×** in the flight number field and type `ZZ9999`.
   - The line under the field says "No route found for ZZ9999. Pick the airports below."
   - Tap **From**, search "paris" and pick CDG; tap **To**, search "rome" and pick FCO. Tap **Done**: the flight card shows CDG → FCO. The flight number had no effect.
   - Type `hello` in the flight number field: "Type a flight number such as BA117 or U2 1234, or pick the airports below." The airport picker works as above.
6. **The keyboard and landscape.** With the keyboard up, the whole setup screen scrolls: you can reach the suggestion's button, From and To, and the airport results. Rotate to landscape and back: what you typed and the suggestion are still there.
7. **Screen reader (TalkBack).** Turn TalkBack on and type `BA117`.
   - TalkBack reads the suggestion when it appears; the route is read as "London Heathrow Airport to John F Kennedy International Airport", not with an arrow.
   - With `BA15`, the leg buttons read "Use the leg from London Heathrow Airport to Singapore Changi Airport" and "… from Singapore Changi Airport to Sydney Kingsford Smith International Airport".
   - Turn TalkBack off.
8. **Update flight data: Wi-Fi only.** Turn airplane mode off, Wi-Fi off and mobile data on. In flight setup tap **Update flight data**.
   - "Not on Wi-Fi. Connect to Wi-Fi, or allow mobile data in Stored maps." Nothing is downloaded (Settings → Apps → Flyoff → Mobile data shows no new use).
9. **Update flight data: the download.** Only after the maintainer has published the `routes-data` release (run the "Flight data" workflow on `main`). On Wi-Fi, tap **Update flight data**.
   - Before the release exists: "Couldn't reach Flyoff's flight data. Check the connection and try again."
   - Once it exists, and it is newer than the app's data: "Newer flight routes from <date>: a 1.1 MB download. Wi-Fi only." with **Not now** and **Download**. Nothing has downloaded yet but the manifest (a few hundred bytes).
   - Tap **Download**: "Downloading flight routes (1.1 MB)…" then "Flight routes updated to <date>." Tap **OK**: the line shows the new date. BA117 still suggests LHR → JFK (unless the data changed).
   - Tap **Update flight data** again: "Flight routes are up to date (<date>)."
   - Swipe Flyoff away from Recents, turn airplane mode on and open it again: flight setup shows the new date, and suggestions still work offline.
10. **Mobile data, when allowed.** Open **Stored maps** (from the flight card), turn on **Use mobile data for route maps** (its note says it is also for flight data updates), then repeat step 8: the check runs on mobile data. Turn the setting off again.
11. **No network while tracking.** On Wi-Fi, set up a flight and tap **Start tracking**. Tap **Change**, then **Update flight data**: "Flyoff uses no network while tracking. Update before or after the flight." Close setup without changing the flight, and stop tracking.

## Results

| Date | Phone / Android version | Steps passed | Update tested against the release (step 9)? | Notes |
|---|---|---|---|---|
| | | | | |
