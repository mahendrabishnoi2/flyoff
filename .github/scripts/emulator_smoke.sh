#!/usr/bin/env bash
# Smoke test Flyoff on a running emulator: install, grant location, launch,
# feed fake GPS fixes, tap "Follow me", rotate, and check for crashes. Then set
# up a flight (LHR to JFK), check the route is all on screen, run the flight
# simulator and check its stats and that real GPS is off meanwhile, set up a
# flight across the antimeridian, and simulate without location permission.
# Screenshots, UI dumps and logcat go to OUT_DIR.
#
# Usage: emulator_smoke.sh <debug-apk> <out-dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG="io.github.mahendrabishnoi2.flyoff"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() { echo "FAIL: $*" >&2; adb logcat -d > "$OUT/logcat.txt" || true; exit 1; }

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1: $(python3 -I "$SCRIPTS/png_colours.py" "$OUT/$1.png") colours"; }

# Dump the UI to OUT/<name>.xml. uiautomator gives up ("could not get idle
# state") while the screen keeps changing, so retry, and never read an old dump.
ui() {
  local i
  for i in 1 2 3 4 5; do
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    if adb shell cat /sdcard/ui.xml > "$OUT/$1.xml" 2> /dev/null && grep -q "<hierarchy" "$OUT/$1.xml"; then
      return 0
    fi
    sleep 1
  done
  fail "could not dump the UI ($1)"
}

expect_text() { grep -q "$2" "$OUT/$1.xml" || fail "'$2' not on screen ($1)"; }

# The helpers below match a regex against each node's text and content-desc
# (see ui_find.py); Compose puts a merged element's words in one or the other.
has() { python3 -I "$SCRIPTS/ui_find.py" has "$OUT/$1.xml" "$2"; }

expect() { has "$1" "$2" || fail "'$2' not on screen ($1)"; }

reject() { if has "$1" "$2"; then fail "'$2' unexpectedly on screen ($1)"; fi; }

# Centre "x y" of the first node matching $2 in dump $1.
find_xy() { python3 -I "$SCRIPTS/ui_find.py" center "$OUT/$1.xml" "$2" || fail "no '$2' to tap ($1)"; }

tap_xy() { adb shell input tap $1; }

# Centre "x y" of the node matching $2, re-dumping as $1 until it stops moving:
# a panel that is still resizing (e.g. right after Stop) would make a stale
# position land on the wrong control.
settled_xy() {
  local name="$1" pattern="$2" xy prev i
  xy=$(find_xy "$name" "$pattern")
  for i in 1 2 3 4 5; do
    sleep 1
    ui "$name"
    prev="$xy"
    xy=$(find_xy "$name" "$pattern")
    [ "$xy" = "$prev" ] && break
  done
  [ "$xy" = "$prev" ] || fail "'$pattern' kept moving ($name)"
  echo "$xy"
}

tap() {
  local xy
  xy=$(settled_xy "$1" "$2")
  tap_xy "$xy"
}

# "true" or "false": whether the "Follow me" chip in dump $1 is on. The state is
# on the chip, not on its label (ui_find.py attr reads the control).
follow_me() { python3 -I "$SCRIPTS/ui_find.py" attr "$OUT/$1.xml" "^Follow me$" checked || fail "no 'Follow me' ($1)"; }

# Check screenshot $1 shows the whole route: wide, and not running off the screen.
route_on_screen() { python3 -I "$SCRIPTS/route_span.py" "$OUT/$1.png" || fail "the route isn't all on screen ($1.png)"; }

# How many GPS listeners the app has registered now; the dump goes to OUT/$1.txt.
gps_listeners() {
  adb shell dumpsys location > "$OUT/$1.txt"
  python3 -I "$SCRIPTS/gps_listeners.py" "$OUT/$1.txt" "$PKG" || fail "no GPS provider in dumpsys location ($1.txt)"
}

# A number read from the first node matching $2, whose first group is the number.
read_number() { python3 -I "$SCRIPTS/ui_find.py" number "$OUT/$1.xml" "$2" || fail "no '$2' to read ($1)"; }

# Dump the UI as $1 until something matches $2, for up to $3 seconds (default 20).
wait_for() {
  local name="$1" pattern="$2" limit="${3:-20}" start=$SECONDS
  while true; do
    ui "$name"
    if has "$name" "$pattern"; then return 0; fi
    if ((SECONDS - start >= limit)); then fail "waited ${limit}s for '$pattern' ($name)"; fi
    sleep 1
  done
}

# Pick an airport in the open flight setup: type $2 into the search field, then
# tap the result row whose description starts with that code.
pick_airport() {
  local step="$1" code="$2"
  adb shell input text "$code"
  wait_for "$step" "^$code, " 20
  tap "$step" "^$code, "
}

reject_text() { if grep -q "$2" "$OUT/$1.xml"; then fail "'$2' unexpectedly on screen ($1)"; fi; }

no_crash() {
  if adb logcat -d -b crash | grep -q "$PKG"; then
    adb logcat -d -b crash > "$OUT/crash.txt"
    fail "app crashed ($1), see crash.txt"
  fi
  adb shell pidof "$PKG" > /dev/null || fail "app not running ($1)"
}

tap_text() {
  local bounds
  bounds=$(grep -o "text=\"$2\"[^>]*bounds=\"[^\"]*\"" "$OUT/$1.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)
  [ -n "$bounds" ] || fail "no '$2' button to tap ($1)"
  # bounds="[x1,y1][x2,y2]" -> centre
  read -r x1 y1 x2 y2 < <(echo "$bounds" | tr -c '0-9\n' ' ')
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# Feed fixes moving east along 51.47N at about 10 km altitude and 450 knots.
# `geo fix` takes longitude first, then altitude (m), satellites and speed (knots).
fly() {
  local lon
  for lon in -0.45 -0.42 -0.39 -0.36 -0.33; do
    adb emu geo fix "$lon" 51.47 10000 8 450
    sleep 1
  done
}

echo "== Install and launch"
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
adb shell cmd location set-location-enabled true
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c
adb logcat -b crash -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 8
no_crash "launch"
shot 01-launch
python3 -I "$SCRIPTS/png_colours.py" "$OUT/01-launch.png" 40 > /dev/null
ui 01-launch
reject_text 01-launch "The map didn"

echo "== GPS fixes"
fly
sleep 2
no_crash "gps"
ui 02-gps
reject_text 02-gps "Waiting for GPS"
expect_text 02-gps "GPS alt"
shot 02-gps
# The control for the simulator's "GPS off" check below: the listener shows here.
listeners=$(gps_listeners dumpsys-02-gps)
[ "$listeners" -ge 1 ] || fail "no GPS listener while tracking ($listeners, dumpsys-02-gps.txt)"

echo "== Follow me"
tap_text 02-gps "Follow me"
fly
sleep 3
no_crash "follow"
shot 03-follow
python3 -I "$SCRIPTS/png_colours.py" "$OUT/03-follow.png" 40 > /dev/null

echo "== Rotate"
adb shell settings put system user_rotation 1
sleep 4
no_crash "rotate"
ui 04-rotated
expect_text 04-rotated "GPS alt"
shot 04-rotated
adb shell settings put system user_rotation 0
# Let the activity finish recreating in portrait before leaving it.
sleep 3

echo "== Background and back"
adb shell input keyevent KEYCODE_HOME
# GPS must stop in the background. A slow emulator can deliver onStop a few
# seconds late, so wait for it rather than reading the first dump.
for i in $(seq 15); do
  sleep 1
  listeners=$(gps_listeners dumpsys-location-background)
  [ "$listeners" = "0" ] && break
done
[ "$listeners" = "0" ] || fail "GPS still on 15 s after going to the background ($listeners listeners, dumpsys-location-background.txt)"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 3
fly
no_crash "resume"
ui 05-resumed
expect_text 05-resumed "GPS alt"
shot 05-resumed

echo "== Flight setup: LHR to JFK"
ui 06-before-setup
# Step 03 turned "Follow me" on (and it survived rotation and the background);
# turn it off so the camera shows the whole route.
[ "$(follow_me 06-before-setup)" = "true" ] || fail "'Follow me' should still be on (06-before-setup)"
tap 06-before-setup "^Follow me$"
sleep 1
ui 06-follow-off
[ "$(follow_me 06-follow-off)" = "false" ] || fail "'Follow me' didn't turn off (06-follow-off)"
tap 06-follow-off "^Set up flight$"
wait_for 06-setup "^From: Choose an airport$"
pick_airport 06-pick-origin LHR
wait_for 06-origin-picked "^From: LHR " 10
pick_airport 06-pick-destination JFK
wait_for 06-picked "^To: JFK " 10
tap 06-picked "^Done$"
wait_for 06-route "^Simulate flight$" 10
# The header reads "LHR → JFK"; screen readers get the airport names.
expect 06-route "LHR → JFK|^Flight from London Heathrow"
expect 06-route "^[0-9,]+ km route$"
no_crash "flight setup"
sleep 3 # the camera's fit animation
shot 06-route
python3 -I "$SCRIPTS/png_colours.py" "$OUT/06-route.png" 40 > /dev/null
route_on_screen 06-route

echo "== Flight simulator"
tap 06-route "^Simulate flight$"
wait_for 07-sim-ready "^Simulated position" 10
expect 07-sim-ready "^Start$"
expect 07-sim-ready "^Remaining: "
tap 07-sim-ready "^300 times real time$"
ui 07-sim-300
expect 07-sim-300 "^Start$"
# Start, Pause and Resume are one button that keeps its place. While the replay
# runs the screen changes every second and uiautomator can't dump it, so the
# button is tapped where it was, and the UI is only read while paused.
play=$(settled_xy 07-sim-300 "^Start$")
tap_xy "$play"
sleep 3
no_crash "simulator start"
shot 07-sim-start
tap_xy "$play"
wait_for 07-sim-paused "^Resume$" 10
expect 07-sim-paused "^Time left: "
expect 07-sim-paused "^ETA: [0-9]{2}:[0-9]{2} "
expect 07-sim-paused "^Speed: [0-9,]+ km/h$"
before=$(read_number 07-sim-paused "^Remaining: ([0-9.,]+) km$")
# Real GPS is off while simulating: no listener, and a real fix (at JFK, which
# would make Remaining about 0) doesn't reach the stats.
listeners=$(gps_listeners dumpsys-07-sim)
[ "$listeners" = "0" ] || fail "GPS still on while simulating ($listeners listeners, dumpsys-07-sim.txt)"
adb emu geo fix -73.78 40.64 10 8 0
sleep 2
ui 07-sim-geo-fix
expect 07-sim-geo-fix "^Resume$"
still=$(read_number 07-sim-geo-fix "^Remaining: ([0-9.,]+) km$")
[ "$still" = "$before" ] || fail "a real GPS fix changed the simulated stats ($before km, then $still km)"
tap 07-sim-paused "^Resume$"
sleep 6
no_crash "simulator running"
shot 08-sim-later
tap_xy "$play"
wait_for 08-sim-paused "^Resume$" 10
after=$(read_number 08-sim-paused "^Remaining: ([0-9.,]+) km$")
echo "distance remaining: $before km, then $after km"
python3 -I -c "import sys; sys.exit(0 if 0 < float(sys.argv[2]) < float(sys.argv[1]) else 1)" "$before" "$after" ||
  fail "distance remaining didn't go down while simulating ($before km, then $after km)"

echo "== Simulator survives rotation"
adb shell settings put system user_rotation 1
sleep 4
no_crash "simulator rotate"
wait_for 09-sim-rotated "^Resume$" 10
expect 09-sim-rotated "^Simulated position"
shot 09-sim-rotated
adb shell settings put system user_rotation 0
sleep 4
wait_for 09-sim-back "^Resume$" 10
again=$(read_number 09-sim-back "^Remaining: ([0-9.,]+) km$")
[ "$again" = "$after" ] || fail "paused replay moved across rotation ($after km, then $again km)"
tap 09-sim-back "^Stop$"
wait_for 10-sim-stopped "^Simulate flight$" 10
reject 10-sim-stopped "^Simulated position"
no_crash "simulator stop"
# Real GPS is back. Feed it fixes now: a fix arriving later would grow the panel
# under the next tap (CI run 45924020380 tapped beside "Change").
fly
wait_for 10-gps-back "^Remaining: " 15
listeners=$(gps_listeners dumpsys-10-gps-back)
[ "$listeners" -ge 1 ] || fail "GPS didn't come back after Stop ($listeners, dumpsys-10-gps-back.txt)"
shot 10-sim-stopped

echo "== Flight across the antimeridian: SYD to LAX"
tap 10-gps-back "^Change$"
wait_for 11-change "^From: LHR " 10
tap 11-change "^From: "
sleep 1
pick_airport 11-pick-origin SYD
wait_for 11-origin-picked "^From: SYD " 10
tap 11-origin-picked "^To: "
sleep 1
pick_airport 11-pick-destination LAX
wait_for 11-picked "^To: LAX " 10
tap 11-picked "^Done$"
wait_for 11-antimeridian "SYD → LAX|^Flight from Sydney" 10
no_crash "antimeridian"
sleep 3
shot 11-antimeridian
python3 -I "$SCRIPTS/png_colours.py" "$OUT/11-antimeridian.png" 40 > /dev/null
route_on_screen 11-antimeridian

echo "== Simulator without location permission (and the flight kept across a restart)"
# Revoking a permission kills the app, so this also restarts it.
adb shell pm revoke "$PKG" android.permission.ACCESS_FINE_LOCATION
adb shell pm revoke "$PKG" android.permission.ACCESS_COARSE_LOCATION
adb shell am start -W -n "$PKG/.MainActivity"
wait_for 12-restarted "^Simulate flight$" 30
expect 12-restarted "SYD → LAX|^Flight from Sydney"
expect 12-restarted "^Allow location$"
tap 12-restarted "^Simulate flight$"
wait_for 12-sim-ready "^Start$" 10
play=$(settled_xy 12-sim-ready "^Start$")
tap_xy "$play"
sleep 3
shot 12-sim-no-permission
tap_xy "$play"
wait_for 12-sim-paused "^Resume$" 10
expect 12-sim-paused "^Remaining: [0-9.,]+ km$"
expect 12-sim-paused "^Simulated position"
tap 12-sim-paused "^Stop$"
wait_for 12-sim-stopped "^Simulate flight$" 10
no_crash "simulator without permission"

adb logcat -d > "$OUT/logcat.txt"
echo "PASS: all smoke checks"
