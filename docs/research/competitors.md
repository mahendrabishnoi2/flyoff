# Flyoff: competitors and open-source projects

*Checked on 2026-11-25. The sources were Google Play and App Store listings, developer websites, and shallow clones of every open-source repo listed here (README, LICENSE and last commit read; no code was built or run). A "?" means no evidence was found. Data sources and licensing are covered in [data-sources-validation.md](data-sources-validation.md).*

## 1. Features we need

| # | Feature |
|---|---|
| F1 | Live GPS position fully offline, in airplane mode |
| F2 | Works with **no pre-download** (world map built into the app) |
| F3 | Optional detailed map along the route, downloaded before the flight |
| F4 | Flight setup: origin and destination airports, flight-number suggestion, great-circle route drawn |
| F5 | Stats: altitude, ground speed, heading, distance and time remaining, ETA |
| F6 | "What's below me" (cities, landmarks), offline |
| F7 | No account |
| F8 | **Free, with no ads and no limits** |

## 2. Store apps

All of them are **closed source**.

| App (platform) | F1 | F2 | F3 | F4 | F5 | F6 | F7 no account | F8 pricing | Installs / rating / last update |
|---|---|---|---|---|---|---|---|---|---|
| **Flight Map: offline tracker** (Android; iOS in TestFlight) | Y, and estimates position when GPS drops | Y (about 3 MB app) | N (simple map only) | Arrival-airport picker; route avoids closed airspace; multi-leg needs a paid tier | Y, all of them | Pro only | Y | **First flight free** (+2 by referral), then £3.99 / £7.99 one-off; no ads | 100+ / none / Nov 2026 |
| **Flymap** (Android, iOS) | Y | N | Y (a few hundred MB, plus Wikipedia) | Airports; flight number ? | Speed, altitude, progress | Y, offline | ? | **Paid to go offline:** $1.99 a flight or $3.99/week to $39.99/year | 10K+ / none / Oct 2026 |
| **Flyover Country** (Android, iOS) | Y | **N** (must save the flight before takeoff) | Y (satellite imagery, 3D terrain, size shown) | Start and end points | **?** (no evidence of ETA or stats) | **Y, rich:** geology, Wikipedia, landmarks | ? | **Free, no ads, no in-app purchases** | **100K+ / 4.3★ (790)** / Sep 2026 |
| **SkyDot** (Android, iOS) | Y | Partial (world map downloads on first launch) | One detailed region per ad watched | **Flight-number lookup**, great circle, multi-leg | Y, all of them | Nearest city and country, offline | Y | **Ads**; 5 saved flights free, then $2.99/month to $19.99 lifetime | 1K+ / none / Nov 2026 |
| **Off 2** (Android, iOS) | Y (+ barometer) | Y (3D globe built in) | High-resolution globe (~423 MB) in Pilot Mode | Boarding-pass scan, flight number, multi-leg | Altitude, ETA (details in Pilot Mode) | Pilot Mode only | Y | Core free, no ads; **Pilot Mode $1.89** one-off | 500+ / none / Nov 2026 |
| **Flight Mode Map** (Android, iOS) | Y | Partial (saved on first online launch) | Satellite + 3D terrain (Pro) | Destination airport, great circle | Y, plus cabin altitude | Pro only | Y | **Ads**; Pro $7.99/month to $69.99 lifetime | 100+ / none / Nov 2026 |
| **SkyLocation** (Android, iOS) | Partial (coordinates, no map evident) | Y | N | Boarding-pass scan (Pro) | Altitude and speed free; ETA in Pro | Nearest city | Y | $5.99 Pro | 1K+ / none / Oct 2026 |
| **Offlight** (Android) | Y | ? | Y? | Airport picker on a map or by search | ETA, speed, heading | ? | ? | Appears free | 500+ / none / Oct 2026 |
| **Inflight Flight Tracker** (Android) | Y | ? | ? | Destination search (**needs internet** in the free version) | Unspecified | ? | Y | Ads; $4.99 to go offline and ad-free | 500+ / none / Jul 2026 |
| **GPS Airplane** (Android) | Y | Y (borders + 1,250 cities) | N | **None** (no route) | Speed, altitude, climb rate; no ETA | Country and nearest city | Y | **Free, no ads** | 50+ / none / Nov 2026 |
| Skyty (iOS only) | Y | Y | Detailed world map, Premium | 47k airports, reads data from inflight Wi-Fi portals | Y, incl. height above ground | Y | Y | Basic free; Premium $2.99 | – |
| Inflight Map (iOS only) | Y | Y | Online maps only | Airport search | Y | N | Y | $3.99 upfront | – |

### Does any of them cover our features for free?

**No.** No Android app gives F1–F6 free and without ads. The closest are:

| Closest free option | What it lacks |
|---|---|
| **Flyover Country** (free, no ads, 100K+) | A pre-download is required (no map built in). No evidence of ETA, speed or altitude. It's a "learn what's below you" app, not a flight tracker. |
| **Off 2** (free core, no ads) | Points of interest and detailed stats need the $1.89 Pilot Mode. Only 500+ installs. |
| **SkyDot** (free core) | Ads; 5 saved flights; detailed maps cost one ad each. |
| **Flight Map** | Free for **one flight** only. |
| **GPS Airplane** (free, no ads) | No route, ETA or flight setup. |

**Gaps nobody fills:**

- A free Android app that combines a built-in world map, a great-circle route with ETA, and offline "what's below me".
- Free GPX/KML track export. On Android only Flight Map plans it, in its paid Pro tier; on iOS it's in paid apps only.
- A Wear OS companion.
- Open source: no store app has public code.

## 3. Open-source projects

None of the store apps above is open source. A paper calls Flyover Country "built with open-source tools", but no repository exists, and it is now run by Flyover Country LLC. F-Droid and IzzyOnDroid have **no passenger in-flight map**, only pilot tools and logbooks. Codeberg and GitLab searches found nothing.

GitHub has several **new, unpublished projects** (all from 2026, 0–6 stars):

| Project | License | Stack | What it does | Last commit | Can we reuse it? |
|---|---|---|---|---|---|
| [eldadgalker-dev/FlightInfo](https://github.com/eldadgalker-dev/FlightInfo) | **BSD-3** | Android, Kotlin + Compose, MapLibre 11.5 | GPS plus an estimate along the route when GPS drops, great circle, speed/altitude/ETA, foreground service, boarding-pass scan, flight log and replay. Natural Earth, OurAirports. Tested on a real MUC→TLV flight. | 2026-11-24 | **Yes, high value.** Same stack. Its GPS-gap estimator, geodesy, tracking service and boarding-pass parser are worth reusing. (Its release signing key is committed to the repo; don't copy it.) |
| [acbrandao/planemode](https://github.com/acbrandao/planemode) | **MIT** | Android, Kotlin + Compose + Hilt, MapLibre 13.x | GPS tracking, ETA, flight-phase detection, altitude profile, satellite view, **corridor download from OpenFreeMap through MapLibre's offline regions**, a flight simulator | 2026-11-24 | **Yes, high value.** Closest to our planned map design. The offline map manager, calculations and simulator are worth reusing. |
| [3aKHP/Stratos](https://github.com/3aKHP/Stratos) | Apache-2.0 | Android, Kotlin, osmdroid | Instrument panel (incl. Mach), GNSS sky plot, corridor tile prefetch | 2026-06-19 (stale) | Partly. Instrument and GNSS UI ideas only; its ArcGIS tile source has doubtful terms. |
| [upanihacks/birdwatcher](https://github.com/upanihacks/birdwatcher) | **CC0** | Python (handheld ADS-B) | `tools/build_routes.py` packs about 620k VRS routes into 3 MB | 2026-10-16 | **Yes**, for the route-packing script |
| [tonywestonuk/flightglance](https://github.com/tonywestonuk/flightglance) | MIT | iOS, SwiftUI | GPS, great circle, ETA, low-power position estimates between fixes | 2026-10-05 | Algorithms only (Swift) |
| [Luchowl/flybook](https://github.com/Luchowl/flybook) (on F-Droid) | MIT | Android | Flight **logbook** with great-circle arcs, no live tracking | 2026-11-11 | Airport and airline data handling |
| [rco-Tech/FlightMap](https://github.com/rco-Tech/FlightMap) | MIT | Web, Three.js | Globe with GPS relayed from a phone or external receiver | 2026-10-06 | Low |
| [alexeygrigorev/flight-map](https://github.com/alexeygrigorev/flight-map) | **No licence** | Android, Kotlin, MapLibre | Estimates position from time only (no GPS), corridor cities, weather | 2026-10-30 | **Study only.** Its `docs/DATA-SOURCES.md` is a good cross-check. |
| [anuragraj12/flight-window](https://github.com/anuragraj12/flight-window) | No licence | PWA, MapLibre GL JS | Left/right-side points of interest, corridor download, boarding-pass scan, window-side advice | 2026-11-24 | Study only (UX ideas) |
| [magebeans/window-seat](https://github.com/magebeans/window-seat) | No licence | PWA, MapLibre GL JS + PMTiles | Protomaps corridor, offline Wikipedia summaries | 2026-11-21 | Study only (PMTiles corridor pipeline) |
| [ajadsingh/flight_experience_flutter](https://github.com/ajadsingh/flight_experience_flutter) | No licence | Flutter | GPS, HUD, OSM tile cache (against the OSM tile policy) | 2026-10-06 | Study only |

**General offline map apps:**

- **Organic Maps and CoMaps (Apache-2.0):** show the GPS position in flight once you have downloaded the regions along the route. They have no flight setup, route, ETA, world view, handling of GPS gaps or "below me" feature.
- **OsmAnd (GPL-3.0):** has speed and altitude widgets, but no flight concept.

**Licence compatibility.** This repo is **GPL-3.0**.

- MIT, BSD-3, Apache-2.0 and CC0 code can all be copied into it. Keep the original copyright and licence notices. For Apache code, also carry its NOTICE file. BSD-3 forbids using the author's name to endorse our app.
- GPL-3.0 code, such as OsmAnd, is also compatible.
- Repos with **no licence** are all-rights-reserved. Read them for ideas, but don't copy code.

## 4. What this means for Flyoff

1. **We have room to stand out.** Being free with no ads, open source, and having a built-in world map with route, ETA and an offline "what's below me" is a combination no store app offers.
   - The real competition is Flyover Country for free, rich content and Flight Map for the polished tracker experience.
   - Free GPX export and Wear OS are cheap extra wins.
2. **Don't start from scratch.** FlightInfo (BSD-3) and planeMode (MIT) already do much of our core on our exact stack.
   - **Option A:** fork one of them, most likely planeMode for its OpenFreeMap corridor download, and bring in FlightInfo's GPS-gap estimator and boarding-pass scanner.
   - **Option B:** start clean and copy specific files.
   - Both repos are about a month old with a single author and almost no stars, so review the code properly before relying on it.
3. **Reach out.** Both authors are actively building the same thing. Collaborating or contributing upstream may beat competing.
4. Use birdwatcher's CC0 `build_routes.py` as the starting point for the route-packing step.
