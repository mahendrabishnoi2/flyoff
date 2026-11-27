"""Count an app's GPS listeners in `adb shell dumpsys location` output (stdlib only).

Reads the "gps provider:" block under "Location Providers:" (headed
"gps provider [mock]:" while a test provider stands in for GPS) and counts the
lines in its "listeners:" list whose identity (uid/package[tag]/id) names the
package. The history and event log further down are ignored, so the count
is what the app has registered now.

Usage: python3 -I gps_listeners.py <dumpsys.txt> <package> [--max-interval SECONDS]
Prints the count. Exits 2 when the file has no gps provider block, so a failed
dump never reads as "no listener".

With --max-interval it also exits 3 when one of the package's GPS listeners is
throttled: marked as in the background or not permitted ("{bg}", "{na}" or
"{bg, na}" after the identity), inactive (" (inactive)" after the whole
registration), or with an interval longer than SECONDS. Android throttles a
background app's location to a few fixes an hour; a foreground service is
exempt, so a tracking listener must show neither.
"""
import re
import sys

UNITS = {"d": 86400.0, "h": 3600.0, "m": 60.0, "s": 1.0, "ms": 0.001}
DURATION = re.compile(r"(\d+)(ms|d|h|m|s)")
INTERVAL = re.compile(r"Request\[@\+?([0-9dhms]+)")
# AOSP's LocationProviderManager.Registration.toString() prints the flags as a
# set ("{bg, na}") between the identity and the request, and
# ListenerMultiplexer.dump() appends " (inactive)" after the whole line.
FLAGS = re.compile(r"^\S+ \{(?:bg|na)(?:, (?:bg|na))?\} ")
INACTIVE = re.compile(r"\(inactive\)\s*$")
GPS_HEADERS = ("gps provider:", "gps provider [mock]:")


def indent(line):
    return len(line) - len(line.lstrip(" "))


def gps_listeners(lines, package):
    """The number of [package]'s listeners on the gps provider, or None if there is no such block."""
    found = gps_listener_lines(lines, package)
    return None if found is None else len(found)


def interval_seconds(line):
    """The request interval in a listener line ("Request[@+5s0ms ..." is 5.0), or None if it has none."""
    m = INTERVAL.search(line)
    if not m:
        return None
    return sum(int(n) * UNITS[unit] for n, unit in DURATION.findall(m.group(1)))


def throttled(line, max_interval):
    """Why the listener [line] is throttled, or None when it isn't."""
    if FLAGS.search(line):
        return "marked as background or not permitted"
    if INACTIVE.search(line):
        return "inactive"
    seconds = interval_seconds(line)
    if seconds is None:
        return "no interval"
    if seconds > max_interval:
        return f"interval {seconds:g} s"
    return None


def gps_listener_lines(lines, package):
    """[package]'s listener lines on the gps provider, or None if there is no such block."""
    in_providers = False
    providers_indent = provider_indent = None
    in_gps = in_listeners = False
    found = None
    for line in lines:
        text = line.strip()
        if not text:
            continue
        depth = indent(line)
        if not in_providers:
            if text == "Location Providers:":
                in_providers, providers_indent = True, depth
            continue
        if depth <= providers_indent:
            break  # the end of the provider list
        if in_gps and depth <= provider_indent:
            in_gps = in_listeners = False
        if not in_gps:
            if text in GPS_HEADERS:
                in_gps, provider_indent, found = True, depth, []
            continue
        if in_listeners and depth <= listeners_indent:
            in_listeners = False
        if in_listeners:
            parts = text.split()[0].split("/")
            if len(parts) >= 2 and parts[1].split("[")[0] == package:
                found.append(text)
        elif text == "listeners:":
            in_listeners, listeners_indent = True, depth
    return found


def main(argv):
    max_interval = None
    if len(argv) == 5 and argv[3] == "--max-interval":
        max_interval = float(argv[4])
    elif len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        found = gps_listener_lines(f, argv[2])
    if found is None:
        print(f"{argv[1]}: no gps provider in the dump", file=sys.stderr)
        return 2
    print(len(found))
    if max_interval is not None:
        reasons = [(line, throttled(line, max_interval)) for line in found]
        bad = [(line, why) for line, why in reasons if why]
        for line, why in bad:
            print(f"throttled ({why}): {line}", file=sys.stderr)
        if bad:
            return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
