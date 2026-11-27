"""Check Flyoff's route map download lines in a logcat dump (stdlib only).

RouteMaps logs, with tag "Flyoff":
  download-region id=.. route=LHR-CDG kind=ROUTE part=1/1 zooms=6-7 planned_tiles=.. required_tiles=.. required_resources=..
  download-region-done id=.. ... tiles=.. tile_bytes=.. bytes=..
  download-done route=.. kind=.. part=1/1 tiles=.. bytes=.. limit_tiles=3000 limit_bytes=50000000

Prints one line per region and per download, and exits 1 when there is no
finished download, or one went over OpenFreeMap's limits (3,000 tiles and
50,000,000 bytes per download, over its regions).

Usage: python3 -I download_log.py <logcat.txt>
"""
import re
import sys

MAX_TILES = 3000
MAX_BYTES = 50_000_000
FIELDS = re.compile(r"(\w+)=(\S+)")


def records(text, event):
    """The key=value fields of each line logging [event]."""
    marker = re.compile(r"\b" + re.escape(event) + r" ")
    for line in text.splitlines():
        if "Flyoff" not in line:
            continue
        m = marker.search(line)
        if m:
            yield dict(FIELDS.findall(line[m.end():]))


def check(text):
    """(report lines, problems)."""
    report, problems = [], []
    for r in records(text, "download-region"):
        report.append("region %s %s %s zooms %s: planned %s tiles, MapLibre requires %s tiles (%s resources)" % (
            r.get("id"), r.get("route"), r.get("part"), r.get("zooms"), r.get("planned_tiles"), r.get("required_tiles"), r.get("required_resources")))
    for r in records(text, "download-region-done"):
        report.append("region %s %s %s zooms %s done: %s tiles, %s bytes" % (
            r.get("id"), r.get("route"), r.get("part"), r.get("zooms"), r.get("tiles"), r.get("bytes")))
    done = list(records(text, "download-done"))
    for r in done:
        tiles, size = int(r["tiles"]), int(r["bytes"])
        report.append("download %s %s %s: %d tiles, %d bytes" % (r.get("route"), r.get("kind"), r.get("part"), tiles, size))
        if tiles > MAX_TILES or size > MAX_BYTES:
            problems.append("download %s %s %s is over the limits: %d tiles, %d bytes" % (r.get("route"), r.get("kind"), r.get("part"), tiles, size))
    for r in records(text, "download-stopped"):
        problems.append("download %s %s %s stopped: %s (%s)" % (r.get("route"), r.get("kind"), r.get("part"), r.get("problem"), r.get("reason")))
    if not done:
        problems.append("no finished download in the log")
    return report, problems


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        report, problems = check(f.read())
    for line in report:
        print(line)
    for line in problems:
        print("PROBLEM: " + line)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
