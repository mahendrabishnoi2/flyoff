#!/usr/bin/env bash
# Smoke test Flyoff on a running emulator: install, grant location, launch,
# feed fake GPS fixes, tap "Follow me", rotate, and check for crashes. Then set
# up a flight (LHR to JFK), check the route is all on screen, run the flight
# simulator and check its stats and that real GPS is off meanwhile. Then the
# Milestone 3 checks: "GPS ready", tracking in a foreground service with its
# notification, recording with the screen off, the estimated position (and its
# grey dot) during a GPS drop-out and recording again after it, tracking in the
# background, Resume after Android kills the app, Stop, simulated GPS gaps and
# the Battery Saver warning. Last, set up a flight across the antimeridian, and
# simulate without location permission.
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

# Like gps_listeners, but also fails when one of the app's GPS listeners is
# throttled (background, inactive, or an interval over 60 s): see gps_listeners.py.
gps_listeners_unthrottled() {
  local code=0 count
  adb shell dumpsys location > "$OUT/$1.txt"
  count=$(python3 -I "$SCRIPTS/gps_listeners.py" "$OUT/$1.txt" "$PKG" --max-interval 60) || code=$?
  [ "$code" = "3" ] && fail "the app's GPS listener is throttled ($1.txt)"
  [ "$code" = "0" ] || fail "no gps provider in dumpsys location ($1.txt)"
  echo "$count"
}

# The tracking service's state: foreground-location, foreground, started or absent.
service_state() {
  adb shell dumpsys activity services "$PKG" > "$OUT/$1.txt"
  python3 -I "$SCRIPTS/tracking_dumps.py" service "$OUT/$1.txt" "$PKG" FlightTrackingService || fail "not a services dump ($1.txt)"
}

# How many tracking notifications for LHR → JFK are posted (its title is
# "Tracking LHR → JFK"; matched without the arrow, in case dumpsys escapes it).
tracking_notifications() {
  adb shell dumpsys notification --noredact > "$OUT/$1.txt"
  python3 -I "$SCRIPTS/tracking_dumps.py" notification "$OUT/$1.txt" "$PKG" flight_tracking "Tracking LHR .{1,8} JFK" ||
    fail "not a notification dump ($1.txt)"
}

# Check the location dot's colour in screenshot $1: $2 is "live" (MapLibre's blue,
# #4a90e2) or "estimated" (Flyoff's grey, #a1b0c0). The dot paints about 500 pixels
# of its colour; the map has none of that blue and only 16-29 pixels of that grey
# (CI run 45943161253's screenshots). See dot_colour.py.
dot_colour() {
  local blue grey
  blue=$(python3 -I "$SCRIPTS/dot_colour.py" "$OUT/$1.png" 4a90e2)
  grey=$(python3 -I "$SCRIPTS/dot_colour.py" "$OUT/$1.png" a1b0c0 3)
  echo "dot pixels in $1: $blue blue, $grey grey"
  if [ "$2" = live ]; then
    [ "$blue" -ge 150 ] && [ "$grey" -lt 100 ] || fail "the dot isn't the live blue ($1.png: $blue blue, $grey grey pixels)"
  else
    [ "$grey" -ge 150 ] && [ "$blue" -lt 100 ] || fail "the dot isn't the estimate's grey ($1.png: $blue blue, $grey grey pixels)"
  fi
}

# Run "$2 $1" (a state function above) until it prints $3, for up to $4 seconds.
wait_state() {
  local name="$1" probe="$2" want="$3" limit="$4" start=$SECONDS got
  while true; do
    got=$($probe "$name")
    [ "$got" = "$want" ] && return 0
    if ((SECONDS - start >= limit)); then fail "$probe is '$got', not '$want', after ${limit}s ($name.txt)"; fi
    sleep 1
  done
}

# The number of recorded track points the panel shows, from a fresh dump $1.
track_points() {
  ui "$1"
  read_number "$1" "^Recording track · ([0-9,]+) points?$"
}

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

# Dump the UI as $1 until nothing matches $2, for up to $3 seconds (default 20).
wait_gone() {
  local name="$1" pattern="$2" limit="${3:-20}" start=$SECONDS
  while true; do
    ui "$name"
    if ! has "$name" "$pattern"; then return 0; fi
    if ((SECONDS - start >= limit)); then fail "'$pattern' still on screen after ${limit}s ($name)"; fi
    sleep 1
  done
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
# GPS gaps are off: the simulated fix is always current, never an estimate.
reject 08-sim-paused "^Estimated position"
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
# The panel is taller than the landscape screen; only its cards may scroll out of
# view, not the "Follow me" chip above them (CI run 45927068621 clipped it to 24 of 53 px).
tall=$(python3 -I "$SCRIPTS/ui_find.py" height "$OUT/08-sim-paused.xml" "^Follow me$") || fail "no 'Follow me' in 08-sim-paused"
wide=$(python3 -I "$SCRIPTS/ui_find.py" height "$OUT/09-sim-rotated.xml" "^Follow me$") || fail "no 'Follow me' in landscape (09-sim-rotated)"
[ "$wide" -ge $((tall - 2)) ] || fail "'Follow me' is cut off in landscape ($wide of $tall px, 09-sim-rotated)"
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

echo "== GPS ready check"
fly
wait_for 13-gps-ready "^GPS ready" 15
expect 13-gps-ready "^Start tracking$"
shot 13-gps-ready

echo "== Start tracking"
# The emulator is API 35: grant notifications so no dialog comes up.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
# Fresh fixes, so the panel doesn't switch to an estimate under the tap.
fly
tap 13-gps-ready "^Start tracking$"
wait_for 14-tracking "^Recording track · " 15
expect 14-tracking "^Stop tracking$"
reject 14-tracking "^Simulate flight$"
no_crash "start tracking"
wait_state dumpsys-14-services service_state foreground-location 10
wait_state dumpsys-14-notification tracking_notifications 1 10
shot 14-tracking
dot_colour 14-tracking live

echo "== Screen off while tracking"
before=$(track_points 15-before-sleep)
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys power > "$OUT/dumpsys-15-power.txt"
if grep -q "mWakefulness=Awake" "$OUT/dumpsys-15-power.txt"; then fail "the screen didn't turn off (dumpsys-15-power.txt)"; fi
# 35 fixes along 51.47N, one a second: about 7 reach the app at its 5 s interval.
i=0
for lon in $(LC_ALL=C seq -0.30 0.02 0.38); do
  adb emu geo fix "$lon" 51.47 10000 8 450
  i=$((i + 1))
  if [ "$i" = 20 ]; then
    listeners=$(gps_listeners_unthrottled dumpsys-15-screen-off)
    [ "$listeners" -ge 1 ] || fail "no GPS listener with the screen off ($listeners, dumpsys-15-screen-off.txt)"
    wait_state dumpsys-15-services-screen-off service_state foreground-location 1
  fi
  sleep 1
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
wait_for 15-woken "^Recording track · " 20
no_crash "screen off"
after=$(track_points 15-woken)
echo "track points: $before before the screen went off, $after after"
[ "$after" -ge $((before + 4)) ] || fail "fixes sent with the screen off weren't recorded ($before points, then $after)"
shot 15-after-screen-off

echo "== GPS drop-out while tracking"
fly
# The emulator's GNSS re-sends its last `geo fix` every second with a fresh time
# (CI run 45943161253), so pausing `geo fix` is no drop-out. Instead a test
# provider that never reports stands in for gps. The app's registration carries
# over: registrations live in LocationProviderManager, and setMockProvider() ->
# MockableLocationProvider.setProviderLocked() stops the real provider and hands
# the current request to the new one (AOSP android15-release). The shell's
# `cmd location` calls are checked as com.android.shell (AppOpsManager.resolvePackageName
# maps the shell uid to it), so that package needs the mock-location app-op.
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider gps --supportsAltitude --supportsSpeed --supportsBearing
adb shell cmd location providers set-test-provider-enabled gps true
listeners=$(gps_listeners_unthrottled dumpsys-16-mocked)
grep -q "^ *gps provider \[mock\]:" "$OUT/dumpsys-16-mocked.txt" || fail "gps isn't a test provider (dumpsys-16-mocked.txt)"
[ "$listeners" -ge 1 ] || fail "the app's GPS listener didn't survive the test provider ($listeners, dumpsys-16-mocked.txt)"
# No fixes from here on: after 10 s the dot becomes an estimate and keeps moving.
wait_for 16-estimate "^Estimated position · last GPS fix" 25
first=$(read_number 16-estimate "^Remaining: ([0-9.,]+) km$")
shot 16-estimate
dot_colour 16-estimate estimated
sleep 10
wait_for 16-estimate-later "^Estimated position · last GPS fix" 10
later=$(read_number 16-estimate-later "^Remaining: ([0-9.,]+) km$")
echo "estimated distance remaining: $first km, then $later km"
python3 -I -c "import sys; sys.exit(0 if float(sys.argv[2]) < float(sys.argv[1]) else 1)" "$first" "$later" ||
  fail "the estimate didn't move on without fixes ($first km, then $later km)"
no_crash "drop-out"

echo "== GPS back after the drop-out"
points=$(read_number 16-estimate-later "^Recording track · ([0-9,]+) points?$")
adb shell cmd location providers remove-test-provider gps
adb shell appops set com.android.shell android:mock_location default
fly
wait_gone 16-gps-back "^Estimated position" 20
listeners=$(gps_listeners_unthrottled dumpsys-16-gps-back)
grep -q "^ *gps provider:" "$OUT/dumpsys-16-gps-back.txt" || fail "gps is still a test provider (dumpsys-16-gps-back.txt)"
[ "$listeners" -ge 1 ] || fail "the app's GPS listener is gone after the drop-out ($listeners, dumpsys-16-gps-back.txt)"
fly
after=$(track_points 16-gps-back-points)
echo "track points: $points during the drop-out, $after after GPS came back"
[ "$after" -gt "$points" ] || fail "fixes after the drop-out weren't recorded ($points points, then $after)"
shot 16-gps-back
dot_colour 16-gps-back live
no_crash "GPS back"

echo "== Background while tracking"
adb shell input keyevent KEYCODE_HOME
sleep 3
listeners=$(gps_listeners_unthrottled dumpsys-17-background)
[ "$listeners" -ge 1 ] || fail "tracking lost its GPS listener in the background ($listeners, dumpsys-17-background.txt)"
wait_state dumpsys-17-services service_state foreground-location 1
adb shell am start -W -n "$PKG/.MainActivity"
fly
wait_for 17-back "^Recording track · " 15
no_crash "background while tracking"

echo "== Tracking after Android kills the app"
before=$(read_number 17-back "^Recording track · ([0-9,]+) points?$")
# force-stop kills the process without the service's onDestroy, as when Android
# ends the app for memory: the "tracking wanted" mark stays behind.
adb shell am force-stop "$PKG"
wait_state dumpsys-17-killed-services service_state absent 10
adb shell am start -W -n "$PKG/.MainActivity"
wait_for 17-interrupted "^Android closed Flyoff while it was recording" 30
reject 17-interrupted "^Recording track"
tap 17-interrupted "^Resume$"
wait_for 17-resumed "^Recording track · " 15
reject 17-resumed "^Android closed Flyoff"
wait_state dumpsys-17-resumed-services service_state foreground-location 10
resumed=$(read_number 17-resumed "^Recording track · ([0-9,]+) points?$")
echo "track points: $before before the kill, $resumed after Resume"
# At most the point being written when the process died may be lost.
[ "$resumed" -ge $((before - 1)) ] || fail "Resume didn't continue the track ($before points, then $resumed)"
no_crash "resume after kill"

echo "== Stop tracking"
fly
ui 18-before-stop
tap 18-before-stop "^Stop tracking$"
wait_for 18-stopped "^Start tracking$" 15
wait_state dumpsys-18-services service_state absent 10
wait_state dumpsys-18-notification tracking_notifications 0 10
no_crash "stop tracking"
shot 18-stopped

echo "== Simulator with GPS gaps"
tap 18-stopped "^Simulate flight$"
wait_for 19-sim-ready "^Start$" 10
reject 19-sim-ready "^Start tracking$"
tap 19-sim-ready "^300 times real time$"
ui 19-sim-300
[ "$(python3 -I "$SCRIPTS/ui_find.py" attr "$OUT/19-sim-300.xml" "^GPS gaps$" checked)" = "false" ] || fail "'GPS gaps' should start off (19-sim-300)"
tap 19-sim-300 "^GPS gaps$"
ui 19-gaps-on
[ "$(python3 -I "$SCRIPTS/ui_find.py" attr "$OUT/19-gaps-on.xml" "^GPS gaps$" checked)" = "true" ] || fail "'GPS gaps' didn't turn on (19-gaps-on)"
# LHR to JFK lasts about 77 s at 300x; the first gap is from 30 % to 40 % of it,
# about 23.0 s to 30.7 s after Start. Pause in its middle (26.9 s; the taps take a
# few tenths of a second), then look: the estimate stays on screen while paused.
play=$(settled_xy 19-gaps-on "^Start$")
tap_xy "$play"
sleep 26.5
tap_xy "$play"
wait_for 19-sim-gap-paused "^Resume$" 10
expect 19-sim-gap-paused "^Estimated position · last GPS fix"
expect 19-sim-gap-paused "^Simulated position"
shot 19-sim-gap
dot_colour 19-sim-gap estimated
tap 19-sim-gap-paused "^Stop$"
wait_for 19-sim-stopped "^Simulate flight$" 10
no_crash "simulator gaps"

echo "== Battery Saver warning"
fly
ui 20-before-saver
reject 20-before-saver "^Battery Saver is on$|^Battery Saver may turn GPS off"
# Battery Saver can't be on while charging, so unplug the emulator's battery,
# and use the location mode that turns GPS off with the screen off.
adb shell dumpsys battery unplug
adb shell settings put global battery_saver_constants "location_mode=1"
adb shell cmd power set-mode 1
sleep 2
adb shell dumpsys power > "$OUT/dumpsys-20-power.txt"
wait_for 20-battery-saver "^Battery Saver is on$|^Battery Saver may turn GPS off" 20
shot 20-battery-saver
adb shell cmd power set-mode 0
adb shell settings delete global battery_saver_constants
adb shell dumpsys battery reset
wait_gone 20-saver-off "^Battery Saver is on$|^Battery Saver may turn GPS off" 20
no_crash "battery saver"

echo "== Flight across the antimeridian: SYD to LAX"
tap 20-saver-off "^Change$"
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
