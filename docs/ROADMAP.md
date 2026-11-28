# Flyoff roadmap

Flyoff is a free, open-source (GPL-3.0) Android app that shows your plane's live position on a map during a flight, with no internet.

We build in **small milestones**. Each milestone ends with an app you can install and test on a real phone before starting the next. We don't start a milestone until the previous one works.

Background research: [data sources and licensing](research/data-sources-validation.md) and [competitors](research/competitors.md).

## Principles

- **It always works offline.** The app must be useful even if the user downloaded nothing. Downloads only add detail.
- **The riskiest things come first.** GPS in airplane mode and the offline map come before polish.
- **Every milestone is testable without flying.** A flight simulator arrives in Milestone 2.
- **Free, with no ads, no account and no tracking.**

## Status

| # | Milestone | Status |
|---|---|---|
| 0 | Research and validation | ✅ Done |
| 1 | Map and GPS dot | 🧪 Merged; verified in the cloud and on the CI emulator; [phone test](testing/m1-phone-test.md) pending |
| 2 | Flight setup and stats | 🧪 Merged; verified in the cloud and on the CI emulator; [phone test](testing/m2-phone-test.md) pending |
| 3 | Reliable tracking | 🧪 Merged; verified in the cloud and on the CI emulator; [phone test](testing/m3-phone-test.md) pending |
| 4 | Route map download | 🧪 Code-complete; verified in the cloud and on the CI emulator; [phone test](testing/m4-phone-test.md) pending |
| 5 | Flight-number suggestion | 🧪 Code-complete; verified in the cloud and on the CI emulator; [phone test](testing/m5-phone-test.md) pending |
| 6 | Extras | 🧪 Flight log, summary and GPX/KML export code-complete; verified in the cloud and on the CI emulator; [phone test](testing/m6-phone-test.md) pending. “What’s below me” and the dark theme planned |
| – | Later | Ideas |

---

## Milestone 1: Map and GPS dot

**Goal:** prove the core idea. A map works with no internet, and the phone's GPS position shows on it.

**Scope:**
- An Android project in Kotlin, Jetpack Compose and Gradle (Kotlin DSL, version catalog).
- MapLibre Native Android (version 13.7.1 or later, which can load a PMTiles file from the app's assets).
- A world map up to zoom 5, cut from OpenFreeMap's planet PMTiles file, reduced to the layers and Latin/English names our style uses (7.3 MB; 56 MB unreduced), and bundled as an app asset (`pmtiles://asset://`).
- One simple map style (OpenMapTiles schema), with Latin fonts only.
- A location-permission request, and a live GPS dot that follows the user.
- The map credit "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors" shown on the map.
- A script, kept in the repo, that rebuilds the bundled map file.

**Not in this milestone:** airports, routes, downloads, background tracking.

**Done when:**
- `./gradlew assembleDebug lintDebug testDebugUnitTest` pass.
- A screenshot shows the bundled world map rendering with our style (headless render or emulator).
- The arm64-v8a release APK is 30 MB or less. A universal APK carries MapLibre for four CPU types, so it's measured per CPU type.
- **On a real phone (maintainer):** with airplane mode on, the app opens, shows the world map and shows a moving GPS dot on a walk or drive.

## Milestone 2: Flight setup and stats

**Goal:** the user can set up a flight and see useful numbers.

**Scope:**
- Bundled airports: OurAirports filtered to those with scheduled service and an IATA code, each with its IANA time zone and search keywords (4,077 airports, 369 KB; 155 KB compressed). ✅ Built by `tools/airports/build_airports.py`, with offline search (`AirportSearch`).
- An origin and destination picker that searches by city, airport name or IATA code, offline.
- The great-circle route drawn on the map.
- Stats: altitude, ground speed, heading, distance flown and remaining, time remaining, and ETA in destination local time.
- **A flight simulator:** a debug mode that replays a fake GPS track along the route at chosen speeds, so later milestones can be tested on the ground.
- Unit tests for the geodesy maths: great-circle distance, bearing, interpolation and cross-track distance.

**Done when:** a simulated LHR→JFK flight shows a correct route, a moving dot and plausible stats.

## Milestone 3: Reliable tracking

**Goal:** tracking survives a real flight.

**Scope:**
- A foreground service with a persistent notification, so tracking continues with the screen off.
- A "GPS ready" check before takeoff: get a position fix while still at the gate.
- Detect battery saver set to "GPS off when screen off" (Android 9+) and warn the user.
- When GPS drops out, keep the dot moving along the route from the last known speed, shown as an estimate.
- Keep the battery and screen-on behaviour sensible.

**Done when:**
- A simulated flight with GPS gaps behaves correctly.
- A real test (car, train or flight) with the screen off records a full track.

## Milestone 4: Route map download

**Goal:** more map detail along the route.

**Scope:**
- Download a strip about 350 km wide along the route at zoom 8 from OpenFreeMap, using MapLibre's offline downloader with a route-shaped region.
- Follow OpenFreeMap's terms: at most 3,000 tiles and 50 MB per download, at most 4 parallel requests, a unique User-Agent, and only when the user starts it. Long routes are split into several downloads.
- Show the size before downloading, default to Wi-Fi only, and show a "Ready for offline ✓" badge.
- A storage screen, and auto-deletion 3 days after landing.
- An optional zoom-9 "detailed" download (Wi-Fi only).

**Done when:** a corridor for London–New York downloads within the limits and shows in airplane mode.

## Milestone 5: Flight-number suggestion

**Goal:** quicker setup for users who know their flight number.

**Scope:**
- A build script that packs the VRS standing-data routes (CC0, about 630k) into a compact format of about 3 MB. ✅ `tools/routes/build_routes.py`: the 390,386 callsigns with an all-digit number (the other 38% can't map to a flight number) in a 1.08 MB file with its own airport list, a format version and the data's date.
- Match a flight number to a callsign: convert the IATA airline code to ICAO, then look up the number. Show the result as a **suggestion the user confirms**, never as fact. Airport selection stays the main input. ✅ An optional field in flight setup (`routes/FlightLookup`, `ui/FlightNumberSection`); the flight number only fills the airports and isn't stored.
- For multi-stop routes, the user picks the leg. ✅
- A weekly data update, downloaded from our own GitHub Release. ✅ `.github/workflows/routes.yml` publishes to the `routes-data` release from `main`; "Update flight data" downloads it on the user's tap. Not live until the maintainer runs the workflow on `main`.

**Done when:** BA117 suggests LHR→JFK, and an unknown flight falls back cleanly to the airport picker.

## Milestone 6: Extras

**Scope:**
- A flight log and history. 🧪 Code-complete (`flight/FlightLogStore`, `tracking/FlightLog`, `ui/FlightLogScreens`).
- A summary screen after landing. 🧪 Code-complete (`flight/FlightSummary`).
- GPX/KML track export (no free Android app has this). 🧪 Code-complete (`flight/TrackExport`).
- "What's below me": the nearest city or region and the country, offline. Planned (a later PR).
- Dark "cockpit" map theme. Planned (a later PR).

**Done when (flight log, summary and export):**
- Every tracked flight is kept after it ends, whether stopped, landed or interrupted and resumed, with its route, start and end times, distance flown and decimated track. ✅ Each flight is one gzipped text file in `filesDir/flights` (about 11–12 kB per flight-hour, measured in `FlightLogStoreTest` and on the CI emulator), copied from `track.log` whenever tracking ends, so a flight in progress during an upgrade is kept too. The log stays out of cloud backup.
- Simulated flights are kept, clearly marked, once the replay touches down (a replay stopped early isn't kept). ✅
- History lists the flights newest first with date, route, duration and distance; a flight opens to its summary and can be deleted; History has an empty state. ✅
- The summary opens when tracking ends (the plane landed and slowed below 50 km/h near the destination, or the user tapped Stop) and from History. It shows the route, the track on the offline map, distance flown against the great-circle distance, duration, max altitude, max ground speed, average speed and the time on an estimated position. ✅
- GPX 1.1 and KML 2.2 export through Android's file picker (`ACTION_CREATE_DOCUMENT`): track points with UTC time and elevation, and the origin and destination as waypoints or placemarks. Exports hold recorded GPS fixes only, never dead-reckoned positions. ✅ Writers unit-tested (parsed back as XML); KML uses `gx:Track` for per-point times.
- No network use anywhere in this work. ✅

## Before the first public release

- **A credits screen** reachable from the app, listing every bundled data source and font with its licence:
  - OpenFreeMap, OpenMapTiles and © OpenStreetMap contributors (ODbL) for the map
  - OurAirports (public domain)
  - timezone-boundary-builder (ODbL, © OpenStreetMap contributors), which the airport list is derived from
  - Noto Sans (SIL OFL)
  - VRS standing data (CC0)

  ✅ **More options (⋮) → Credits and licences** on the main screen (`ui/CreditsScreen`, list in `credits/Credits`). It also lists Natural Earth and the open-source libraries in the APK, and opens each licence's full text from `assets/licenses/` offline; links open in the browser only on a tap. The on-map credit and its (i) button are unchanged. Checked by unit tests and the CI emulator; [phone test](testing/credits-phone-test.md) pending.

## Agent skills

Skills are added at the milestone that needs them, not all at once. **When you start a milestone, install the skills listed for it** before writing code. The full procedure is in [AGENTS.md](../AGENTS.md#agent-skills).

| When | Skill (`--skill`) | Repo (`owner/repo`) | Notes |
|---|---|---|---|
| ✅ Milestone 1 (installed) | `maplibre-native-gl-js-parity` | `maplibre/maplibre-agent-skills` | PMTiles and other differences between MapLibre on Android and on the web |
| ✅ Milestone 1 (installed) | `android-permissions-security` | `android/skills` | Location permission flow; foreground service later |
| ✅ Milestone 1 (installed) | `android-ux` | `rcosteira79/android-skills` | Material 3 UX review |
| ✅ Milestone 2 (installed) | `testing-setup` | `android/skills` | Guidance only. It adds Hilt, Robolectric, JaCoCo and Dropshots if missing. **Tell it not to**; add a test library only when a test needs it. |
| ✅ Milestone 2 (installed) | `android-testing` | `rcosteira79/android-skills` | Test-first discipline; Compose UI and coroutine test patterns |
| ✅ Milestone 3 (installed, maintainer-approved) | `android-profiler` | `android/skills` | Battery drain and wake locks. **Runs remote code:** its bundled `trace_processor` wrapper downloads and runs a sha256-pinned Perfetto binary, and its recording workflow downloads Perfetto helper scripts from `google/perfetto` `main` (unpinned). Only for profiling on a device you control; read the scripts before running them. |
| ✅ Milestone 3 (installed) | `jetpack-compose-performance` | `android/skills` | Recomposition and frame-time audits |
| Release | `play-policy-insights` | `android/skills` | **Bundles Python scripts** that scrape Play. Review them first, and get the maintainer's OK before installing. |
| Release | `fdroid-publishing` | `ItsAhadun/fdroid-publishing-skill` | Community skill with one author. Re-read it before use. |
| Later (Wear OS) | `wear-compose-m3`, `wear-widgets` | `android/skills` | Only if a Wear OS app is built |

Licence for the `--skill`/repo pairs above: `android/skills` is Apache-2.0; the others are MIT. The upstream licence files for the repos already used are in `.agents/licenses/`.

Assessed and **not** to install: anthropics `frontend-design` (web only), Google's `android-cli` (pipes a remote script into bash), large Compose packs (aldefy, Meet-Miyani), JetBrains/Kotlin skills (multiplatform and server), generic community packs.

## Later (ideas, not committed)

- Terrain and height above ground (Copernicus GLO-30, with the required notices).
- A Wear OS companion app.
- Detailed region packs through Play Asset Delivery.
- Boarding-pass scanning.
- Fonts for non-Latin scripts.
- Release on F-Droid.
