#!/usr/bin/env bash
# Smoke test Flyoff on a running emulator: install, grant location, launch,
# feed fake GPS fixes, tap "Follow me", rotate, and check for crashes.
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

ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null; adb shell cat /sdcard/ui.xml > "$OUT/$1.xml"; }

expect_text() { grep -q "$2" "$OUT/$1.xml" || fail "'$2' not on screen ($1)"; }

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

echo "== Background and back"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell dumpsys location > "$OUT/dumpsys-location-background.txt" || true
adb shell am start -W -n "$PKG/.MainActivity"
sleep 3
fly
no_crash "resume"
ui 05-resumed
expect_text 05-resumed "GPS alt"
shot 05-resumed

adb logcat -d > "$OUT/logcat.txt"
echo "PASS: all smoke checks"
