# Flyoff: validated research on data sources, licensing and sizes

*Checked on 2026-11-25. This replaces the earlier unverified notes.*

Flyoff is a free Android app that shows the plane's live GPS position on an offline map during a flight in airplane mode.

This document records which claims from the earlier research held up, which were wrong, and the numbers we measured ourselves.

**How the checks were done.**

- Every licence and terms claim was checked against a primary source: licence files, terms pages, store listings and official docs.
- Sizes were measured where possible, not taken from blog posts:
  - **Map tile sizes:** read from the directory of the Protomaps daily build `20261124.pmtiles` (v4.15.2, about 140 GB) using HTTP range requests, so no tiles were downloaded. The script is [`scripts/pmsize.py`](scripts/pmsize.py).
  - **Route data:** counted from a clone of the VRS standing-data repo, commit of 2026-11-25. The script is [`scripts/vrs.py`](scripts/vrs.py).
  - **Airports:** counted from today's OurAirports `airports.csv`.

---

## 1. Summary

**The plan holds.** A free, fully offline and legal app is buildable with $0 of ongoing data costs.

Four findings change the design:

1. **The Android niche is crowded.**
   - About 10 Android apps exist, including Flyover Country with 100K+ installs, which appears free.
   - Off 2 and Flight Mode Map are on Android too.
   - Being free and ad-free helps, but quality has to be the differentiator.
2. **Looking up a route by flight number is unreliable.**
   - The VRS route data is keyed by air-traffic callsign, and 38% of callsigns end in letters (e.g. `RYR12AB`). They cannot be mapped back to the flight number a passenger sees.
   - For Ryanair, easyJet and Air France the share is about 80%.
   - So **choosing the origin and destination airports must be the main way to set up a flight.** A flight-number match is only a suggestion the user confirms.
3. **OpenFreeMap explicitly allows offline downloads started by the user.** The limits are 3,000 tiles and 50 MB per download. Per-flight corridors can therefore come straight from OpenFreeMap with no hosting cost.
4. **File size grows 2.2–4.8× per zoom level, not 2×.**
   - Zoom 8 is the right default for the corridor.
   - Zoom 9 must be an optional, Wi-Fi-only download.
   - Zoom 10 is too heavy for land-heavy routes (up to 217 MB).

---

## 2. Competitors (Android)

| App | Status on Android | Pricing | Installs (US Play) |
|---|---|---|---|
| Flight Map: offline tracker (`uk.pmd.flightmap`) | ✓ Claims confirmed. Android 8+, no account, no ads | First flight free, then £3.99 Unlimited / £7.99 Pro (one-off). Up to 2 more free flights through referrals | 100+ |
| Flymap (`app.flymap`) | Partly wrong: it is **subscription-based** | $3.99 a week, $5.99 a month, $39.99 a year, or $1.99 for one flight | 10K+ |
| Flyover Country (`com.ionicframework.app303739`) | **Missed by the earlier research** | Appears free (no ads or in-app purchase badges) | **100K+, 4.3★** |
| Off 2 (`org.looping.offto`) | **On Android** (the earlier research said iPhone-only) | In-app purchases, "no subscription" | 500+ |
| Flight Mode Map (`com.spiderservice.flightmodemap`) | **On Android** (the earlier research said iPhone-only) | Ads, plus Pro as a subscription or lifetime purchase | 100+ |
| SkyDot (`com.evolveratechnologies.skydot`) | Missed | Ads plus in-app purchases ($1.49–$29.99) | 1K+ |
| SkyLocation (`com.skylocation.android`) | Missed | $5.99 in-app purchase | 1K+ |
| Offlight (`com.pixelboot.offlight`) | Missed | No ads or in-app purchase badges | 500+ |
| Inflight Flight Tracker (`com.flighttracker.app.free`) | Missed | Ads, with a one-time purchase to remove them | 500+ |
| GPS Airplane: Offline Speed | Missed | No in-app purchase badge | 50+ |

- **iOS only:** Skyty and Inflight Map ($3.99).
- **Name check:** no app called "FlyOff" or "Fly Off" was found on either store. "Offlight" and "Off 2" are close. The trademark registers (USPTO, EUIPO, UK IPO; classes 9, 39 and 42) were **not searched** and should be checked before launch.

A detailed feature comparison and the open-source landscape are in [competitors.md](competitors.md).

---

## 3. Flight routes (flight number → origin and destination)

| Source | Verdict | Facts |
|---|---|---|
| **VRS standing data** (`vradarserver/standing-data`) | ✓ **Use this. CC0 1.0.** | Crowdsourced from Virtual Radar Server users through the SDM site. A bot commits daily (about 04:49 UTC). Rows carry no per-route dates, so stale routes can't be detected. |
| adsb.lol API | Partly wrong (out of date) | ODbL. Its route lookup *is* the VRS data: it loads `vrs-standing-data.adsb.lol/routes.csv.gz` every hour. Rate limits are fixed quotas now: 500 requests/day per IP without a key, 1,000/day with a free key, 7,200/day for feeders. "Plausible" route is only a geometric check. **Bundle VRS directly instead**: it's CC0 and avoids ODbL share-alike. |
| adsbdb | ✓ Avoid | *"…may not be copied, published, or incorporated into other databases without the explicit permission of David J Taylor, Edinburgh."* |
| OpenFlights routes | ✓ Avoid | *"…ceased providing updates in June 2014. The current data is of historical value only."* |
| OpenSky Network | ✓ Avoid | A live product needs a written licence, *"regardless of the entity's non-profit status."* (Its terms page was unreachable; checked through the search index and the API docs.) |
| FlightAware AeroAPI Personal | ✓ Avoid | July 2026 licence: individuals only. The *"Licensee Shall Not"* list includes *"Use AeroAPI Data for commercial aircraft situational displays."* |
| Aviationstack | ✓ Avoid | Free plan: 100 requests a month, *"Non-Commercial Use"*. |

**Measurements of the VRS routes** (`scripts/vrs.py`):

| Metric | Value |
|---|---|
| Routes (unique callsigns) | **630,099** |
| Direct routes (2 airports) | 595,922 (94.6%) |
| Routes with stops (3+ airports) | 34,177 (5.4%) |
| Callsigns whose number part isn't purely numeric | **239,947 (38.1%)** |
| Ryanair, easyJet, Air France: share with letter suffixes | RYR 40,408 of 48,833; EZY 27,007 of 31,866; AFR 13,294 of 16,667 |
| BA, Lufthansa, United: share with letter suffixes | BAW 2,316 of 4,424; DLH 3,304 of 7,988; UAL 1,055 of 7,011 |
| Compact CSV (callsign, airports) | 11.3 MB raw, **2.97 MB gzip**, 2.02 MB xz |
| SQLite (callsign as primary key, WITHOUT ROWID) | 13.9 MB on disk, 4.2 MB gzip |

What this means:

- "620k routes in 3 MB" (from the Birdwatcher README) is confirmed for the **download** size.
- Queried on the phone as SQLite, it takes about 14 MB. Use a compact custom format to stay small: index airports as 16-bit numbers and group callsigns by airline.
- Flight-number lookup only works where the callsign number equals the flight number (e.g. `BA117` → `BAW117`, `EGLL-KJFK`). Map the IATA airline code to the ICAO one with the VRS `airlines` file.
- **Product decision:** the main input is the origin and destination airports. A flight-number match is shown as a suggestion the user confirms. Multi-stop routes need the user to pick a leg.
- **Weekly updates:** publish a small routes file from our own GitHub Release or Pages, built by CI. Don't have every user pull from the adsb.lol mirror.

---

## 4. Airports

| Claim | Verdict | Facts |
|---|---|---|
| OurAirports is public domain | ✓ | The data repo is licensed under the Unlicense. Files are regenerated daily. |
| `airports.csv` is about 12 MB | ✓ | 12,763,918 bytes, 86,389 rows |
| "About 9,000 commercial airports" | ✗ | 9,053 is the count with an **IATA code**, which includes closed fields and heliports. With `scheduled_service=yes` it's **4,336**. Scheduled, large or medium together make **6,348**. |
| Under 1 MB once filtered | ✓ | 7 columns (ident, iata_code, name, lat, lon, country, municipality): **138 KB gzip** for scheduled-only, 276 KB for IATA-coded |

---

## 5. Map tiles

### 5.1 Sources

| Source | Verdict | Facts |
|---|---|---|
| OpenStreetMap tile servers | ✓ **Never use** | *"Offline use is not permitted on tile.openstreetmap.org."* *"Features such as "Download city/country for offline use" or "Save area for later"… are therefore prohibited."* Such use is *"blocked without notice."* |
| **OpenFreeMap** | ✓ Better than claimed | No limits on views, no registration, no API keys. Weekly world downloads in **Btrfs, MBTiles and PMTiles**. Zoom 0–14, OpenMapTiles schema. **Offline downloads are explicitly allowed:** *"Your app may let users download areas for offline use if each download is: at most 3,000 tiles and at most 50 MB; made with at most 4 parallel requests; sent with a unique User-Agent identifying your app."* Longer routes can be split into several user-started downloads. Anything beyond that needs permission (info@openfreemap.org). The service *"may discontinue it at any time without notice."* |
| Protomaps | ✓ | An ODbL Produced Work; OSM attribution required. Daily builds, zoom 0–15, about 140 GB today (the docs still say "roughly 120 GB"). On hotlinking: *"hotlinking to these downloads are discouraged. Instead, you should copy the tileset to your own Cloud Storage."* Zooms 0–5 use Natural Earth; OSM coastlines start at zoom 6. |
| Google Maps Platform | ✓ **Can't be used** | Terms §3.2.3: no *"pre-fetch, index, store… bulk download"*, no caching. Map Tiles API policies list *"Offline uses"* as banned. The Android SDK has no offline API. Places data must never be shown *on a non-Google map* (§3.2.3(e)). Billing and an API key are required. |

**Recommendation:** use the **OpenFreeMap / OpenMapTiles schema throughout**, so one map style covers everything:

- **Bundled world map:** a zoom 0–5 extract from OpenFreeMap's weekly PMTiles file, loaded with `pmtiles://asset://`.
- **Per-flight corridor:** MapLibre's offline downloader with a route-shaped region, pulling from the public OpenFreeMap server within its terms. Split long corridors into several downloads of 3,000 tiles / 50 MB or less.
- **Fallback:** if OpenFreeMap changes its terms, self-host its weekly PMTiles file (same schema, no style changes) and cut corridors with `pmtiles extract --region`.

### 5.2 Measured sizes (Protomaps build 2026-11-24; `scripts/pmsize.py`)

Each tile's bytes are counted once, even when several tiles share the same data (as ocean tiles do), which is how the file stores them.

**Whole world, by zoom level:**

| Zoom | Tiles | Bytes at this zoom | Total from zoom 0 | Growth from previous zoom |
|---|---|---|---|---|
| 0–4 | 341 | 6.0 MB | 6.0 MB | – |
| 5 | 1,024 | 9.0 MB | **15.0 MB** | 1.5× |
| 6 | 4,096 | 30.1 MB | 45.1 MB | 3.3× |
| 7 | 16,384 | 145.0 MB | 190.1 MB | 4.8× |
| 8 | 65,536 | 372.2 MB | 562.3 MB | 2.6× |
| 9 | 262,144 | 1,027.5 MB | 1,589.8 MB | 2.8× |
| 10 | 1,048,576 | 2,225.2 MB | 3,815.0 MB | 2.2× |

- "World up to zoom 5 is 17 MB" ✓. We measured 15 MB; Simon Willison's 2023 post says 17 MB and estimates 46 MB for zoom 6.
- "Each zoom level roughly doubles" ✗. It grows **2.2–4.8×** at zooms 6–10. (The Protomaps docs repeat the "doubles" rule of thumb.)
- "Continental US, zoom 0–10, is 361 MB" ✓. This comes from VIPTrack's release notes; we measured **386 MB** for the box -125, 24, -66.9, 49.5.
- "A city at 68 MB full detail vs 813 KB to zoom 6" ✗ **Unsourced.** The number isn't in any cited source. Drop it.

**Per-flight corridor**, 350 km wide along the great circle, zooms 6 to N:

| Route | Length | Up to zoom 8 | Up to zoom 9 | Up to zoom 10 |
|---|---|---|---|---|
| London–New York (LHR–JFK) | 5,541 km | 7–10 MB | 24–30 MB | 70–78 MB |
| Delhi–Frankfurt (DEL–FRA), mostly land | 6,121 km | 19–32 MB | 66–88 MB | 183–217 MB |
| Los Angeles–New York (LAX–JFK) | 3,974 km | 9–13 MB | 28–38 MB | 74–87 MB |
| Singapore–Sydney (SIN–SYD), mostly sea | 6,294 km | 2–4 MB | 6–8 MB | 13–17 MB |
| Mumbai–Delhi (BOM–DEL) | 1,137 km | 1–3 MB | 5–7 MB | 12–15 MB |

The ranges come from two ways of counting edge tiles: the lower bound counts tiles whose centre is inside the strip, the upper bound every tile that touches it. Tile counts for the full zoom 6–8 corridor are 65–409. Those are Protomaps sizes; OpenFreeMap tiles use a different schema and were not measured.

- The earlier "10–40 MB at zoom 6–8" ✓ (we measured 2–32 MB).
- The earlier "5–30 MB up to zoom 9–10" ✗. Zoom 9 can reach about 90 MB, and zoom 10 about 220 MB.
- **Decision:** zoom 8 is the default. Zoom 9 is an optional "detailed" download, Wi-Fi only, showing its size first. No zoom 10.

---

## 6. Map engine: MapLibre Native Android

- **Licence:** BSD 2-Clause.
- **PMTiles:** supported since **11.8.0** according to the changelog (the docs say 11.7.0). Accepted URLs: `pmtiles://https://…` and `pmtiles://file://…`. **`pmtiles://asset://…` (a file inside the app package) needs 13.7.1 or later**, per the MapLibre skill `maplibre-native-gl-js-parity`.
- **Offline limitation:** *"PMTiles sources do not support offline pack downloads or caching."* So the corridor download uses the normal offline downloader (`OfflineManager`) on standard XYZ/TileJSON tiles, and the bundled world map is a PMTiles asset.
- **Size** (measured from the 13.7.1 package): `libmaplibre.so` is 13.0 MB on arm64 (9.6 MB on armv7), about 4–4.6 MB compressed. Expect **about 5 MB of download and about 13 MB on the device** per CPU type when shipped as an app bundle.

---

## 7. Fonts (measured from `protomaps/basemaps-assets`)

| Font | All Unicode ranges | Ranges below U+0600 (Latin, Greek, Cyrillic…) |
|---|---|---|
| Noto Sans Regular (256 glyph files) | 6.24 MB | **585 KB** |

- Bundle the ranges below U+0600 for 2–3 font weights (about 1.2–1.8 MB). Download other scripts only when needed.
- Worth checking: MapLibre can draw Chinese, Japanese and Korean labels with the phone's built-in fonts instead of glyph files (the `localIdeographFontFamily` option).

---

## 8. Terrain (optional, later version)

| Source | Verdict | Facts |
|---|---|---|
| Copernicus DEM GLO-30 | ✓ Free and commercial use allowed, but the notice is **incomplete** | Converted data must show: *"produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"*. It must **also** state that *"The organisations in charge of the Copernicus programme… do not incur any liability…"*, and must not imply endorsement. Armenia, Azerbaijan and Moldova were released only in Dec 2023, so the **AWS copy (2021 release) is missing tiles**. Download from the Copernicus Data Space instead. |
| SRTM | Probably public domain (USGS catalog) | Not confirmed on NASA's page. Has voids, so prefer Copernicus. |
| Natural Earth | ✓ Public domain | *"No permission is needed"*. No credit needed. |

---

## 9. Distribution: Google Play

| Claim | Verdict | Facts |
|---|---|---|
| Asset packs up to 1.5 GB each, hosted by Google | ✓ | Free, and for apps as well as games. Total limits: 4 GB for install-time content, 30 GB for on-demand and fast-follow packs, 100 packs at most. |
| Region packs can be refreshed independently | ✗ | Asset packs ship inside the app bundle. **Updating one needs a new app release**, and updating the app invalidates downloaded packs. That's fine for a yearly map refresh, but weekly route data needs its own download. |
| App size limit | Out of date | The base module limit is now **500 MB**. 200 MB is only the "you're on mobile data" warning. |
| Google 2017: "every 6 MB costs about 1% of installs; ~30% more completed downloads at 10 MB than 100 MB" | ✓ | Sam Tolomei, Google Play (2017), via quotes. The Medium original returned 403. |

---

## 10. Ads (only if we ever add them)

- **Expiry:** preloaded ads expire after **about 1 hour** (interstitial, rewarded) or **4 hours** (app-open). Ads fetched at the gate are worthless mid-flight.
- **Policy:** AdMob disallows interstitials *"on app load and when exiting apps"*. A post-flight ad must sit at a natural break, such as after the landing summary.
- No official source says offline impressions are queued and counted later.

---

## 11. GPS in airplane mode

- ✓ The stock Android list of radios that airplane mode turns off is `cell,bluetooth,uwb,wifi,wimax`, which doesn't include GPS. Phone makers can change this list; there are a few user reports of failures.
- **Without internet, the first fix is slow:** a cold start typically takes 2–4 minutes, and a full almanac download takes 12.5 minutes. **Get a fix at the gate before switching on flight mode.**
- **Risk:** on Android 9+, battery saver can be set to *"GPS disabled when screen off"* (`PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`). Use a foreground service, check `getLocationPowerSaveMode()`, and warn the user.
- A window seat helps: the fuselage weakens the signal. All competitors advise it, but no controlled study was found.

---

## 12. Recommended stack and app size

| Part | Choice | Licence and credit |
|---|---|---|
| Map engine | MapLibre Native Android ≥ 13.7.1 | BSD-2 |
| Bundled world map | OpenFreeMap PMTiles extract, zoom 0–5 (about 15 MB) | ODbL Produced Work: *"OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"* |
| Corridor download | OpenFreeMap public tiles through the offline downloader, zoom 8 by default, within the terms limits | Same as above |
| Routes | VRS standing data in a compact custom format, updated weekly from our own GitHub Release | CC0 (credit is optional but nice) |
| Airports | OurAirports, scheduled-only, 7 columns | Unlicense / public domain |
| Terrain (later) | Copernicus GLO-30 from the Copernicus Data Space | Modified-data notice **plus** the no-liability sentence |
| Fonts | Noto Sans, ranges below U+0600 | OFL |

**Estimated download size:** about 25 MB.

- MapLibre: about 5 MB
- World map to zoom 5: 15 MB
- Routes: 3 MB
- Airports: 0.14 MB
- Fonts: 1–2 MB
- App code: a few MB

That matches the earlier 25–30 MB target. Per-flight downloads are 2–32 MB at zoom 8.

**Map attribution (ODbL).** The visible "© OpenStreetMap" credit on the map may collapse to an (i) button after 5 seconds. The full credits screen also lists OpenMapTiles and OpenFreeMap, Natural Earth, VRS standing data, OurAirports and, once terrain is added, Copernicus.

Whether ODbL share-alike applies to vector tiles is unsettled: the OSM Foundation's licensing group hasn't ruled on it. If we add no data of our own and our build scripts are public, we can provide the underlying database on request either way.

---

## 13. Open questions

- Accuracy of VRS routes against real schedules: sample about 200 current flights and measure the hit rate.
- OpenFreeMap tile sizes for our corridors (the measurements above are for the Protomaps schema).
- Trademark search for "Flyoff".
- GPS behaviour in airplane mode on Samsung, Xiaomi and Pixel phones (field test).

## Sources

- Competitors: [Flight Map](https://play.google.com/store/apps/details?id=uk.pmd.flightmap), [Flymap](https://play.google.com/store/apps/details?id=app.flymap), [Flyover Country](https://play.google.com/store/apps/details?id=com.ionicframework.app303739), [Off 2](https://play.google.com/store/apps/details?id=org.looping.offto), [Flight Mode Map](https://play.google.com/store/apps/details?id=com.spiderservice.flightmodemap)
- Routes: [VRS standing data](https://github.com/vradarserver/standing-data), [adsb.lol API docs](https://www.adsb.lol/docs/open-data/api), [adsb.lol API source](https://github.com/adsblol/api), [adsbdb](https://github.com/mrjackwills/adsbdb), [OpenFlights data](https://openflights.org/data), [OpenSky API](https://openskynetwork.github.io/opensky-api/), [AeroAPI Personal licence](https://www.flightaware.com/commercial/aeroapi/AeroAPI_Personal_License_Jul2026_v2.pdf), [Aviationstack pricing](https://aviationstack.com/pricing), [Birdwatcher](https://github.com/upanihacks/birdwatcher)
- Airports and terrain: [OurAirports data](https://github.com/davidmegginson/ourairports-data), [Natural Earth terms](https://www.naturalearthdata.com/about/terms-of-use/), [Copernicus DEM licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf), [Copernicus DEM release notes](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM)
- Maps: [OpenFreeMap terms](https://openfreemap.org/tos/), [OpenFreeMap repo](https://github.com/hyperknot/openfreemap), [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/), [OSM attribution guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines), [Protomaps downloads](https://docs.protomaps.com/basemaps/downloads), [PMTiles CLI](https://docs.protomaps.com/pmtiles/cli), [Protomaps layers](https://docs.protomaps.com/basemaps/layers), [Simon Willison on PMTiles](https://til.simonwillison.net/gis/pmtiles), [VIPTrack v0.7.0](https://github.com/SysAdminDoc/VIPTrack/releases/tag/v0.7.0)
- MapLibre: [PMTiles on Android](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/), [licence](https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md)
- Google: [Maps Platform terms](https://cloud.google.com/maps-platform/terms), [Map Tiles API policies](https://developers.google.com/maps/documentation/tile/policies), [Play size limits](https://support.google.com/googleplay/android-developer/answer/9859372), [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery), [Shrinking APKs, growing installs](https://medium.com/googleplaydev/shrinking-apks-growing-installs-5d3fcba23ce2)
- Ads and GPS: [AdMob ad preloading](https://developers.google.com/admob/android/ad-preloading), [AdMob app-open ads](https://developers.google.com/admob/android/app-open), [AdMob interstitial policy](https://support.google.com/admob/answer/6201362), [Airplane-mode radios](https://developer.android.com/reference/android/provider/Settings.Global#AIRPLANE_MODE_RADIOS), [PowerManager](https://developer.android.com/reference/android/os/PowerManager)
