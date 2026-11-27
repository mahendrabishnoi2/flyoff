"""Count an app's GPS listeners in `adb shell dumpsys location` output (stdlib only).

Reads the "gps provider:" block under "Location Providers:" and counts the
lines in its "listeners:" list whose identity (uid/package[tag]/id) names the
package. The history and event log further down are ignored, so the count
is what the app has registered now.

Usage: python3 -I gps_listeners.py <dumpsys.txt> <package>
Prints the count. Exits 2 when the file has no gps provider block, so a failed
dump never reads as "no listener".
"""
import sys


def indent(line):
    return len(line) - len(line.lstrip(" "))


def gps_listeners(lines, package):
    """The number of [package]'s listeners on the gps provider, or None if there is no such block."""
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
            if text == "gps provider:":
                in_gps, provider_indent, found = True, depth, 0
            continue
        if in_listeners and depth <= listeners_indent:
            in_listeners = False
        if in_listeners:
            parts = text.split()[0].split("/")
            if len(parts) >= 2 and parts[1].split("[")[0] == package:
                found += 1
        elif text == "listeners:":
            in_listeners, listeners_indent = True, depth
    return found


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        count = gps_listeners(f, argv[2])
    if count is None:
        print(f"{argv[1]}: no gps provider in the dump", file=sys.stderr)
        return 2
    print(count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
