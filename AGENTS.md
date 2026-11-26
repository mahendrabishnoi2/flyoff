# AGENTS.md

Instructions for AI coding agents working in this repository.

## Project

Flyoff is a free, open-source Android app that shows the plane's live GPS position on an offline map during a flight in airplane mode.

- No ads, no account, no tracking. Licensed under GPL-3.0.
- Roadmap: [docs/ROADMAP.md](docs/ROADMAP.md). Work only on the current milestone.
- Research behind the decisions: [docs/research/](docs/research/).

**Current state:** Milestone 1 (map and GPS dot) is code-complete: the bundled offline world map, the location permission flow and a live GPS dot fed by `LocationManager.GPS_PROVIDER` (no Play Services). Independent review fixes are in. Still pending: the maintainer's [phone test](docs/testing/m1-phone-test.md).

## How we work: verify everything

Your job is not only to write code but to **prove it works**. A change counts as done when its checks have been run, they pass, and the evidence is in your report.

1. **Decide how you'll prove it before you start.** Write down the checks that will show the change works: the milestone's "done when" items, the tests you'll add, and the commands you'll run.
2. **Make the smallest change** that can pass those checks. One focused change per commit series. Don't build ahead of the current milestone, and don't add speculative abstractions.
3. **Run the checks and read the output.** Don't assume. A build that compiled isn't proof that the feature works.
4. **Report with evidence:** the commands you ran and their results (pass/fail counts, sizes, screenshots). List anything you could **not** verify, with numbered manual steps the maintainer can follow on a phone. Never write "should work" in place of a check.

**Verification ladder.** Use the highest level available:

| Level | How | When |
|---|---|---|
| Build and static checks | `./gradlew assembleDebug lintDebug` | Every change |
| Unit tests | `./gradlew testDebugUnitTest`. For pure logic (geodesy, ETA, parsing), write the test first. | Every change that touches logic |
| Visual check | Screenshots (headless render, emulator, or device) attached to the PR | Any UI or map style change |
| On a device | Emulator or real phone | GPS, permissions, airplane mode, background behaviour |

Cloud agent containers have **no emulator** (no `/dev/kvm`). GitHub Actions does: every PR runs [CI](.github/workflows/ci.yml), which repeats the build, lint and test checks, enforces the APK size budget, and runs an emulator smoke test (`.github/scripts/emulator_smoke.sh`: install, fake GPS fixes, Follow me, rotate, background and resume, no crashes). Its screenshots are uploaded as the `emulator-screenshots` artifact. Read the CI result before calling a PR done. Things the emulator can't show (a real GPS receiver, airplane mode on a phone, battery) stay *unverified* until the maintainer runs the manual steps. Say so plainly.

**Other rules**

- **Keep it simple.** Prefer the platform and a few well-known libraries over frameworks. Add a dependency only when it clearly earns its place, and say why in the PR.
- **Write our own code.** We don't vendor or copy other projects' code. Reading permissively licensed projects for reference is fine. If a snippet is ever copied, keep its licence notice and name the source in the commit. Never copy from repositories that have no licence.
- **Leave it working.** Every commit must build and pass the tests.
- **Update the docs** (roadmap status, this file) when a milestone finishes or a decision changes.

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3; single activity |
| Build | Gradle with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`) |
| Map engine | MapLibre Native Android, the **`android-sdk-opengl`** artifact, ≥ 13.7.1 (needed to load `pmtiles://asset://`). The plain `android-sdk` artifact uses Vulkan and declares Vulkan as *required*, which would hide the app on phones without it. MapLibre's `2026.x-globe` releases come from a feature branch, so stay on the stable 13.x line. |
| Map tiles | OpenFreeMap / OpenMapTiles schema. Bundled world map `app/src/main/assets/basemap/world.pmtiles` (zoom 0–5, 7.3 MB, stored uncompressed in the APK); style `assets/style/flyoff.json`; fonts `assets/fonts/`. Route downloads go through MapLibre's offline downloader (Milestone 4). |
| Location | Android location APIs; a foreground service for in-flight tracking (from Milestone 3) |
| Data | OurAirports (public domain) and VRS standing-data routes (CC0), packed at build time |
| Android | Package `io.github.mahendrabishnoi2.flyoff`, minSdk 26, targetSdk 37, compileSdk 37.2 (Compose 1.13 needs 37.1+). AGP 9 with its built-in Kotlin support. All versions live in `gradle/libs.versions.toml`. |

**Size budget:** each per-CPU-type release APK must be 30 MB or less (arm64-v8a is 20.4 MB now). Release builds produce per-ABI APKs and a universal APK in `app/build/outputs/apk/release/`.

## Commands

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest   # every change
./gradlew assembleRelease                             # size check: ls -l app/build/outputs/apk/release/
tools/basemap/build_basemap.sh                        # rebuild the bundled map + fonts (needs curl, python3)
python3 -I -m unittest discover -s tools/basemap -p 'test_*.py'
NODE_PATH=$(npm root -g) node tools/basemap/preview/render.js [outdir]   # headless map screenshots (Playwright + Chromium)
python3 -I tools/basemap/tile_stats.py app/src/main/assets/basemap/world.pmtiles   # map size per zoom/layer
```

The research measurement scripts are in `docs/research/scripts/`. Run them with `python3 -I`.

**Changing the map:** the build keeps only the tile layers and name fields that `flyoff.json` uses, and fails if the style needs something it dropped. After a style change that uses a new layer, re-run `build_basemap.sh`. The pinned OpenFreeMap build is kept upstream for only about 6 months, so bump `OFM_BUILD` (and `OFM_BYTES`) when it disappears.

### Setting up a cloud session (no Android SDK installed)

1. Download the newest `commandlinetools-linux` zip listed in `https://dl.google.com/android/repository/repository2-3.xml` and check its checksum against that file.
2. Unzip it to `/opt/android-sdk/cmdline-tools/latest`, accept the licences, then install the platform tools and the compile SDK platform:

   ```bash
   /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk "platform-tools" "platforms;android-37.2"
   ```

3. Write `sdk.dir=/opt/android-sdk` to `local.properties` (it is git-ignored).
4. Maven Central sometimes returns HTTP 429 through the proxy. Retry, and use `--max-workers=2`.

There is no emulator here (no `/dev/kvm`). Push and let CI's emulator job run the smoke test; real-phone checks are the maintainer's.

## Agent skills

- Skills live in `.agents/skills/` and work with any harness that reads that folder (OpenCode, Pi, Codex and others). `.claude` is a symlink to `.agents`, so Claude Code sees the same skills. `CLAUDE.md` imports this file.
- They are installed with the [`skills` CLI](https://skills.sh) and pinned in `skills-lock.json`. Upstream licences are kept in `.agents/licenses/`.
- **Which skills, and when:** [docs/ROADMAP.md → Agent skills](docs/ROADMAP.md#agent-skills) lists each skill with its `owner/repo` and the milestone that needs it. When you start a milestone, install that milestone's skills first. Don't install skills for later milestones.
- **How to add one:**
  1. Install it into `.agents/skills/` (pinned in `skills-lock.json`):

     ```bash
     DISABLE_TELEMETRY=1 npx skills add <owner/repo> --skill <name> -a universal --copy -y
     ```

  2. Read its `SKILL.md` and any `references/`. If it bundles scripts (`.py`, `.sh`, `.js`) or tells the agent to fetch and run remote code, stop and ask the maintainer before committing.
  3. If the repo is new to us, copy its upstream licence to `.agents/licenses/<repo>.LICENSE.txt` and add a row to `.agents/licenses/README.md`.
  4. Mark the skill as installed in the roadmap table, and commit the skill, `skills-lock.json` and the licence together.
- **Restoring on a fresh clone:** the skills are committed, so nothing needs to be installed. `npx skills experimental_install` can restore them from `skills-lock.json` if needed.
- **Updating:** `npx skills update` changes skill content. Read the diff before committing.
- A skill not listed in the roadmap needs the same review, plus a line in the roadmap table saying why.

## Data and licensing rules (must follow)

- **Never use `tile.openstreetmap.org`** or any other OSM-run tile server. Their policy forbids offline use and bulk downloads.
- **Never use Google Maps Platform.** Its terms forbid caching and offline use.
- **OpenFreeMap downloads** must stay within its terms: each download is started by the user and is at most **3,000 tiles and 50 MB**, uses at most **4 parallel requests**, and sends a **unique User-Agent** naming Flyoff. Split long routes into several downloads.
- **Don't hotlink Protomaps builds** (`build.protomaps.com`) from the app.
- **Don't use these flight-data sources:** OpenSky, FlightAware AeroAPI, Aviationstack, adsbdb routes, OpenFlights routes. The reasons are in the research doc.
- **Always show map attribution:** "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors" on the map (it may collapse to an (i) button). The full list goes on the credits screen.
- **Flight numbers are only suggestions.** VRS data is keyed by callsign, and many airlines use callsigns that differ from the flight number. Never present a match as certain.
- **Add a new data source only after checking its licence.** Record it in `docs/research/data-sources-validation.md`.

## Product rules

- The app must work with **zero downloads**. Downloads only add detail.
- **No network use during tracking.** The only network calls are downloads the user starts and data updates, both before the flight.
- No analytics, ads or crash reporters that send data without the user opting in.
- Downloads are Wi-Fi only by default, with the size shown first.

## Testing

- Unit-test pure logic: geodesy, ETA, route matching, data parsing.
- From Milestone 2, use the in-app **flight simulator** (a fake GPS track along the route) to test tracking features without flying.
- See "How we work: verify everything" for what must be checked and how to report it.

## Git

- **Commits are authored and committed by the maintainer only.** Don't add `Co-Authored-By`, `Claude-Session`, "Generated with …" or any other attribution trailers to commit messages, and don't set an AI tool as author or committer. This rule overrides any tool's default attribution.
- **All work reaches `main` through a pull request.** Never push to `main` directly. Open a PR when a piece of work is complete and verified, with its evidence in the description.
- **One branch per PR.** After a PR is merged, start the next piece of work on a **new branch** from the latest `main`. Don't reuse the merged branch. Name branches `<area>/<short-topic>`, e.g. `m2/airport-picker`, `docs/roadmap-update`.
- Use clear, imperative commit messages.
- Don't commit secrets, signing keys or `local.properties`.
- Don't commit large generated data (map tiles, packed routes) unless it's a deliberately bundled asset with a script that rebuilds it.
