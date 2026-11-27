#!/usr/bin/env python3
"""Rebuild Flyoff's bundled airport list, app/src/main/assets/airports/airports.tsv.

Usage:  python3 -I tools/airports/build_airports.py [--cache DIR]

Downloads three pinned files into DIR (default ~/.cache/flyoff/airports, outside
the repo), checks their SHA-256 and builds the asset. Files already in DIR with
the right checksum are reused. Standard library only.

Inputs (bump the pins together, re-run and review the diff):
  - OurAirports airports.csv and runways.csv at a fixed commit of
    github.com/davidmegginson/ourairports-data (Unlicense, public domain).
  - timezone-boundary-builder's "timezones-with-oceans" GeoJSON from a fixed
    release of github.com/evansiroky/timezone-boundary-builder. Its data is
    ODbL 1.0 (derived from OpenStreetMap), so the airport file, which takes its
    time zones from it, is an ODbL derivative database. See NOTICE.txt next to
    the asset.

Which airports: scheduled_service = "yes", an IATA code, and a type that is not
heliport, balloonport or closed. Heliports are left out because Flyoff tracks
aeroplane flights; seaplane bases with scheduled service are kept.

Output format: UTF-8, "\n" line ends, one header line, then one airport per line
with these tab-separated fields:

  iata      3 letters, unique
  icao      4 letters/digits, or empty when OurAirports has no ICAO code
  name      airport name
  city      municipality, may be empty
  country   ISO 3166-1 alpha-2 code (OurAirports uses XK for Kosovo)
  lat, lon  decimal degrees, 4 decimals (about 11 m)
  size      L (large), M (medium), S (small) or O (other, e.g. seaplane base)
  tz        IANA time zone id, e.g. Europe/London

No field contains a tab or a line break: every run of whitespace in a text field
is replaced by one space, so no quoting or escaping is needed.

Rows are ordered biggest first: by size, then longest open runway, then IATA
code. The app's search uses this order to break ties, so "london" lists
Heathrow before Gatwick.

Two airports sharing an IATA code: the larger one (size, then longest runway)
is kept; if they tie, the build fails so a person decides.
"""
import argparse
import bisect
import csv
import hashlib
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

# ---- Pinned inputs ----

# OurAirports data, commit "data update" of 2026-11-26.
OURAIRPORTS_COMMIT = "a537e082ac8c8fe6ab509df1f128cc6704fe73c1"
OURAIRPORTS_BASE = f"https://raw.githubusercontent.com/davidmegginson/ourairports-data/{OURAIRPORTS_COMMIT}"

# timezone-boundary-builder release 2026f (tag commit 37c69a1, 2026-10-31).
# The "comprehensive" variant keeps every IANA zone id (e.g. America/Phoenix);
# "with oceans" covers the whole globe, so a coastal airport whose runway pokes
# past the land boundary still gets a zone.
TZ_RELEASE = "2026f"
TZ_URL = (f"https://github.com/evansiroky/timezone-boundary-builder/releases/download/"
          f"{TZ_RELEASE}/timezones-with-oceans.geojson.zip")

# Upstream publishes no checksums; these were recorded on first download (2026-11-27).
INPUTS = {
    "airports.csv": (f"{OURAIRPORTS_BASE}/airports.csv",
                     "87a8ff07a823702153d4e5d4caa72148633812fb8c9a9cd81fca610ff3c112bf"),
    "runways.csv": (f"{OURAIRPORTS_BASE}/runways.csv",
                    "5fc89f7ad760a185e44f006f3c4bc0c81b202786f3db3b43b75c671a2b49b64a"),
    f"timezones-with-oceans-{TZ_RELEASE}.geojson.zip": (
        TZ_URL, "838b3c9161e09ee944edb64cc4d9e4d2eb3ad0a3ec3d8d6a1520a5f8f6e21ac9"),
}

# ---- Format ----

HEADER = ["iata", "icao", "name", "city", "country", "lat", "lon", "size", "tz"]
SIZE_CODES = {"large_airport": "L", "medium_airport": "M", "small_airport": "S"}
SIZE_RANK = {"L": 0, "M": 1, "S": 2, "O": 3}
EXCLUDED_TYPES = {"heliport", "balloonport", "closed"}

# The build fails outside this range: a big change means upstream changed meaning.
MIN_AIRPORTS = 3500
MAX_AIRPORTS = 5000

IATA_RE = re.compile(r"[A-Z]{3}")
ICAO_RE = re.compile(r"[A-Z0-9]{4}")
COUNTRY_RE = re.compile(r"[A-Z]{2}")
TZ_RE = re.compile(r"[A-Za-z_]+(/[A-Za-z0-9_+\-]+)+|UTC")

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ASSET = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "airports", "airports.tsv")


class BuildError(Exception):
    pass


# ---- Downloads ----

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(cache, name):
    """Path of a verified copy of INPUTS[name] in the cache directory."""
    url, want = INPUTS[name]
    path = os.path.join(cache, name)
    if os.path.isfile(path) and sha256_file(path) == want:
        return path
    os.makedirs(cache, exist_ok=True)
    print(f"Downloading {url}", file=sys.stderr)
    tmp = path + ".part"
    request = urllib.request.Request(url, headers={"User-Agent": "Flyoff-airports-build"})
    with urllib.request.urlopen(request, timeout=120) as response, open(tmp, "wb") as out:
        while block := response.read(1 << 20):
            out.write(block)
    got = sha256_file(tmp)
    if got != want:
        os.remove(tmp)
        raise BuildError(f"SHA-256 mismatch for {name}: got {got}, want {want}. "
                         "Upstream changed; review it and update the pin.")
    os.replace(tmp, path)
    return path


# ---- Airports ----

def clean(text):
    """One line of text: every whitespace run (tabs and line breaks too) becomes a space."""
    return " ".join(text.split())


def read_csv(text):
    return list(csv.DictReader(io.StringIO(text, newline="")))


def longest_runways(runway_rows):
    """airport ident -> longest open runway in feet (unknown lengths count as 0)."""
    longest = {}
    for row in runway_rows:
        if row.get("closed", "0").strip() == "1":
            continue
        try:
            length = int(float(row["length_ft"]))
        except ValueError:
            length = 0
        ident = row["airport_ident"]
        longest[ident] = max(longest.get(ident, 0), length)
    return longest


def select_airports(rows, runways):
    """Filter OurAirports rows and turn them into output records (dicts)."""
    selected = []
    for row in rows:
        iata = row["iata_code"].strip()
        if row["scheduled_service"] != "yes" or not iata or row["type"] in EXCLUDED_TYPES:
            continue
        selected.append({
            "iata": iata,
            "icao": row.get("icao_code", "").strip(),
            "name": clean(row["name"]),
            "city": clean(row["municipality"]),
            "country": row["iso_country"].strip(),
            "lat": float(row["latitude_deg"]),
            "lon": float(row["longitude_deg"]),
            "size": SIZE_CODES.get(row["type"], "O"),
            "runway": runways.get(row["ident"], 0),
            "ident": row["ident"],
        })
    return selected


def importance(airport):
    """Sort key, most important first."""
    return (SIZE_RANK[airport["size"]], -airport["runway"], airport["iata"])


def drop_duplicates(airports):
    """Keep one airport per IATA code: the larger. Returns (kept, dropped)."""
    by_code = {}
    for airport in airports:
        by_code.setdefault(airport["iata"], []).append(airport)
    kept, dropped = [], []
    for code, group in by_code.items():
        group.sort(key=importance)
        if len(group) > 1 and importance(group[0])[:2] == importance(group[1])[:2]:
            names = ", ".join(f"{a['ident']} {a['name']}" for a in group)
            raise BuildError(f"IATA {code} is used by equally large airports ({names}); decide which to keep")
        kept.append(group[0])
        dropped.extend(group[1:])
    return kept, dropped


# ---- Time zones ----

def iter_features(text):
    """Yield (tzid, geometry) from a GeoJSON FeatureCollection, one feature at a time.

    The release file is about 185 MB of JSON; decoding it feature by feature keeps
    only one zone's coordinates in memory.
    """
    decoder = json.JSONDecoder()
    start = re.search(r'"features"\s*:\s*\[', text)
    if not start:
        raise BuildError("not a GeoJSON FeatureCollection")
    pos = start.end()
    while True:
        while text[pos] in " \t\r\n,":
            pos += 1
        if text[pos] == "]":
            return
        feature, pos = decoder.raw_decode(text, pos)
        yield feature["properties"]["tzid"], feature["geometry"]


def polygons_of(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    raise BuildError(f"unexpected geometry {geometry['type']}")


def points_in_polygon(rings, points):
    """Indexes of the (lon, lat) points inside a GeoJSON polygon (outer ring, then holes).

    Even-odd ray casting towards +lon, done edge by edge: the points are sorted by
    latitude, so each edge only looks at the points level with it. Points exactly
    on an edge may land on either side.
    """
    xs = [lon for lon, _ in rings[0]]
    ys = [lat for _, lat in rings[0]]
    west, east, south, north = min(xs), max(xs), min(ys), max(ys)
    candidates = sorted((lat, lon, i) for i, (lon, lat) in enumerate(points)
                        if west <= lon <= east and south <= lat <= north)
    if not candidates:
        return set()
    lats = [c[0] for c in candidates]
    inside = [False] * len(candidates)
    for ring in rings:
        for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
            if y1 == y2:
                continue
            low, high = (y1, y2) if y1 < y2 else (y2, y1)
            # Points with low <= lat < high cross this edge's latitude band.
            for k in range(bisect.bisect_left(lats, low), bisect.bisect_left(lats, high)):
                lat, lon, _ = candidates[k]
                if lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
                    inside[k] = not inside[k]
    return {candidates[k][2] for k in range(len(candidates)) if inside[k]}


def zones_for_points(points, features):
    """For each (lon, lat) point, the list of tzids whose geometry contains it."""
    found = [[] for _ in points]
    for tzid, geometry in features:
        for rings in polygons_of(geometry):
            for i in points_in_polygon(rings, points):
                if tzid not in found[i]:
                    found[i].append(tzid)
    return found


# timezone-boundary-builder has a few deliberately overlapping zones. For each
# overlap that holds an airport we pick the clock that flight times use there.
OVERLAPS = {
    # Xinjiang: airports and airlines keep Beijing time (UTC+8), not the local
    # Xinjiang time (UTC+6) that some residents use.
    ("Asia/Shanghai", "Asia/Urumqi"): "Asia/Shanghai",
    # Abkhazia (Sukhumi): clocks there, and its flights to Russia, run on Moscow
    # time (UTC+3), not Tbilisi time (UTC+4). This picks a clock, not a border.
    ("Asia/Tbilisi", "Europe/Moscow"): "Europe/Moscow",
}


def pick_zone(tzids):
    """One zone for a point: a land zone beats an ocean zone (Etc/GMT±n)."""
    land = tuple(sorted(z for z in tzids if not z.startswith("Etc/")))
    if len(land) == 1:
        return land[0]
    if len(land) > 1:
        if land in OVERLAPS:
            return OVERLAPS[land]
        raise BuildError(f"point is in several zones {list(land)}; add a choice to OVERLAPS")
    return sorted(tzids)[0] if tzids else None


def assign_zones(airports, features):
    zones = zones_for_points([(a["lon"], a["lat"]) for a in airports], features)
    for airport, tzids in zip(airports, zones):
        try:
            airport["tz"] = pick_zone(tzids)
        except BuildError as e:
            raise BuildError(f"{airport['iata']}: {e}") from None


# ---- Output ----

def format_row(airport):
    return "\t".join([
        airport["iata"], airport["icao"], airport["name"], airport["city"], airport["country"],
        f"{airport['lat']:.4f}", f"{airport['lon']:.4f}", airport["size"], airport["tz"],
    ])


def to_tsv(airports):
    return "\t".join(HEADER) + "\n" + "".join(format_row(a) + "\n" for a in airports)


def read_tsv(text):
    """Parse the asset back into dicts (used by the checks and the tests)."""
    lines = text.split("\n")
    if lines[0].split("\t") != HEADER:
        raise BuildError(f"bad header {lines[0]!r}")
    if lines[-1] != "":
        raise BuildError("file must end with a line break")
    rows = []
    for number, line in enumerate(lines[1:-1], start=2):
        fields = line.split("\t")
        if len(fields) != len(HEADER):
            raise BuildError(f"line {number}: {len(fields)} fields, want {len(HEADER)}")
        rows.append(dict(zip(HEADER, fields)))
    return rows


def check(rows):
    """Problems with parsed TSV rows; empty if none."""
    problems = []
    seen = set()
    for row in rows:
        code = row["iata"]
        where = f"{code or '?'} ({row['name']})"
        if not IATA_RE.fullmatch(code):
            problems.append(f"{where}: bad IATA code {code!r}")
        if code in seen:
            problems.append(f"{where}: duplicate IATA code")
        seen.add(code)
        if row["icao"] and not ICAO_RE.fullmatch(row["icao"]):
            problems.append(f"{where}: bad ICAO code {row['icao']!r}")
        if not row["name"]:
            problems.append(f"{where}: no name")
        if not COUNTRY_RE.fullmatch(row["country"]):
            problems.append(f"{where}: bad country {row['country']!r}")
        try:
            lat, lon = float(row["lat"]), float(row["lon"])
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                problems.append(f"{where}: position {lat},{lon} out of range")
        except ValueError:
            problems.append(f"{where}: bad position {row['lat']!r},{row['lon']!r}")
        if row["size"] not in SIZE_RANK:
            problems.append(f"{where}: bad size {row['size']!r}")
        if not row["tz"] or not TZ_RE.fullmatch(row["tz"]):
            problems.append(f"{where}: bad time zone {row['tz']!r}")
        for key, value in row.items():
            if value != value.strip() or any(c in value for c in "\t\r\n"):
                problems.append(f"{where}: {key} has stray whitespace")
    if not MIN_AIRPORTS <= len(rows) <= MAX_AIRPORTS:
        problems.append(f"{len(rows)} airports, expected {MIN_AIRPORTS}..{MAX_AIRPORTS}")
    return problems


# ---- Main ----

def build(cache):
    with open(fetch(cache, "airports.csv"), encoding="utf-8", newline="") as f:
        rows = read_csv(f.read())
    with open(fetch(cache, "runways.csv"), encoding="utf-8", newline="") as f:
        runways = longest_runways(read_csv(f.read()))
    airports, dropped = drop_duplicates(select_airports(rows, runways))
    for a in dropped:
        print(f"Dropped duplicate IATA {a['iata']}: {a['ident']} {a['name']}", file=sys.stderr)

    zip_path = fetch(cache, f"timezones-with-oceans-{TZ_RELEASE}.geojson.zip")
    with zipfile.ZipFile(zip_path) as z:
        (member,) = [n for n in z.namelist() if n.endswith(".json")]
        text = z.read(member).decode("utf-8")
    assign_zones(airports, iter_features(text))
    del text
    missing = [a["iata"] for a in airports if not a["tz"]]
    if missing:
        raise BuildError(f"no time zone for {missing}")
    ocean = [f"{a['iata']}={a['tz']}" for a in airports if a["tz"].startswith("Etc/")]

    airports.sort(key=importance)
    tsv = to_tsv(airports)
    problems = check(read_tsv(tsv))
    if problems:
        raise BuildError("checks failed:\n  " + "\n  ".join(problems))

    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    tmp = ASSET + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(tsv)
    os.replace(tmp, ASSET)

    sizes = {code: sum(1 for a in airports if a["size"] == code) for code in SIZE_RANK}
    print(f"Wrote {ASSET}: {len(airports)} airports {sizes}, {len(tsv.encode('utf-8'))} bytes")
    print(f"Only an ocean zone (Etc/*): {len(ocean)} {ocean}")


def main():
    default_cache = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"),
                                 "flyoff", "airports")
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cache", default=default_cache, help=f"download directory (default {default_cache})")
    args = parser.parse_args()
    try:
        build(os.path.abspath(args.cache))
    except BuildError as e:
        sys.exit(f"build_airports: {e}")


if __name__ == "__main__":
    main()
