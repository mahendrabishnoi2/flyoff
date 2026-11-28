#!/usr/bin/env python3
"""Rebuild Flyoff's flight routes file, app/src/main/assets/routes/routes.bin.

Usage:
  python3 -I tools/routes/build_routes.py [--cache DIR]
      Builds the bundled snapshot from the pinned VRS commit into the app's assets.
  python3 -I tools/routes/build_routes.py --latest --out DIR [--cache DIR]
      Builds the newest VRS data into DIR/routes-v1.bin with its manifest
      DIR/routes-v1.json, for the weekly update (.github/workflows/routes.yml).

Input: the VRS standing data (github.com/vradarserver/standing-data, CC0 1.0):
the routes, routes/schema-01/*/*.csv (Callsign, Code, Number, AirlineCode,
AirportCodes), and the airlines, airlines/schema-01/airlines.csv (Code, Name,
ICAO, IATA, ...). It is fetched with git (a shallow, sparse fetch of one commit)
into DIR (default ~/.cache/flyoff/routes, outside the repo). The pinned commit's
CSV files are checked against VRS_CONTENT_SHA256 (see content_sha256). Needs git;
otherwise standard library only.

What is kept: only callsigns whose number part is all digits (BAW117). Flyoff
looks routes up by flight number, and a flight number maps to a callsign only
when the callsign's number is the flight number; a callsign like EZY12AB or
RYR5QF can't be reached from one, so those (about 38%) are dropped. Every
airline with a kept callsign is kept, with its IATA code, so the app can turn
"BA117" into BAW117.

Output format, version FORMAT_VERSION. The file is a gzip stream (RFC 1952,
mtime 0, so a rebuild gives the same bytes) of:

  magic       4 bytes, "FLYR"
  version     varint, FORMAT_VERSION
  date        varint, the build date: the VRS commit's time in Unix seconds (UTC).
              Taken from the data, not the clock, so the build is reproducible
              and "newer" means newer data.
  source      string, the VRS commit id
  airports    varint count, then each airport's code: a string, the ICAO code
              (or for the few VRS has without one, its own 3-letter code). The
              file carries its own list, so it never depends on airports.tsv.
  routes      varint count, then per route: varint stop count (2 or more), then
              each stop's airport index, in the order flown
  airlines    varint count, then per airline: string code (the callsign prefix,
              ICAO, or IATA when the airline has no ICAO code), string IATA code
              (empty when none), string name, varint number of flights
  numbers     for each airline in order, its flight numbers in ascending order:
              the first as it is, then each as (number - previous - 1)
  routes      for each flight, in the same order, varint route index
  flights     varint, the total number of flights (a check), then the stream ends

varint: unsigned LEB128 (7 bits per byte, low bits first). string: varint byte
length, then UTF-8. Airports and routes are sorted by how many flights use them
(ties by code), so the common ones get one-byte indexes; airlines by code.
"""
import argparse
import csv
import glob
import gzip
import hashlib
import io
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

# ---- Pinned input ----

VRS_REPO = "https://github.com/vradarserver/standing-data"
# The daily bot commit of 2026-11-27 04:49 UTC.
VRS_COMMIT = "bd5f5d5af341ed9d75e1758e4a6bb740e8247f93"
# sha256 of the CSV inputs at that commit (content_sha256), recorded on first build.
VRS_CONTENT_SHA256 = "7c4d5d72719a3b3892ab7d1983d78d251722453e1dcea808ea2ffab15149c42d"

ROUTE_GLOB = "routes/schema-01/*/*.csv"
AIRLINES_CSV = "airlines/schema-01/airlines.csv"

# ---- Format ----

MAGIC = b"FLYR"
FORMAT_VERSION = 1
RELEASE_NAME = f"routes-v{FORMAT_VERSION}"

# The build fails outside these: a big change means upstream changed meaning.
MIN_FLIGHTS = 250_000
MAX_FLIGHTS = 1_000_000
MIN_AIRLINES = 1_000
MAX_BYTES = 3_000_000

NUMBER_RE = re.compile(r"[0-9]{1,4}")
CALLSIGN_CODE_RE = re.compile(r"[A-Z]{3}|[A-Z0-9]{2}")
IATA_RE = re.compile(r"[A-Z0-9]{2}")
AIRPORT_RE = re.compile(r"[A-Z0-9]{3,4}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ASSET = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "routes", "routes.bin")


class BuildError(Exception):
    pass


# ---- Fetching ----

def git(repo, *args):
    result = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise BuildError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout.strip()


def latest_commit():
    """The commit id of VRS's default branch now (git ls-remote)."""
    result = subprocess.run(["git", "ls-remote", VRS_REPO, "HEAD"], capture_output=True, text=True)
    if result.returncode != 0:
        raise BuildError(f"git ls-remote: {result.stderr.strip()}")
    commit = result.stdout.split()[0] if result.stdout.split() else ""
    if not COMMIT_RE.fullmatch(commit):
        raise BuildError(f"git ls-remote gave no commit: {result.stdout!r}")
    return commit


def fetch(cache, commit):
    """A checkout of [commit]'s routes and airlines in the cache; returns its directory."""
    repo = os.path.join(cache, "standing-data")
    if not os.path.isdir(os.path.join(repo, ".git")):
        os.makedirs(repo, exist_ok=True)
        git(repo, "init", "-q")
        git(repo, "remote", "add", "origin", VRS_REPO)
    try:
        have = git(repo, "rev-parse", "HEAD")
    except BuildError:
        have = None
    if have != commit:
        print(f"Fetching {VRS_REPO} {commit}", file=sys.stderr)
        git(repo, "-c", "protocol.version=2", "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", commit)
        git(repo, "sparse-checkout", "set", "--no-cone", "/routes/schema-01/", "/airlines/schema-01/", "/LICENSE")
        git(repo, "checkout", "-q", "--detach", commit)
    return repo


def commit_time(repo):
    return int(git(repo, "log", "-1", "--format=%ct"))


def input_files(root):
    """The CSV inputs under [root], as sorted paths relative to it."""
    routes = sorted(os.path.relpath(p, root).replace(os.sep, "/") for p in glob.glob(os.path.join(root, ROUTE_GLOB)))
    if not routes:
        raise BuildError(f"no route files {ROUTE_GLOB} in {root}: did the VRS layout change?")
    if not os.path.isfile(os.path.join(root, AIRLINES_CSV)):
        raise BuildError(f"no {AIRLINES_CSV} in {root}: did the VRS layout change?")
    return routes + [AIRLINES_CSV]


def content_sha256(root, paths):
    """sha256 over each input's path and bytes, in order: the pin for a commit's data."""
    digest = hashlib.sha256()
    for path in paths:
        with open(os.path.join(root, path), "rb") as f:
            data = f.read()
        digest.update(f"{path}\n{len(data)}\n".encode())
        digest.update(data)
    return digest.hexdigest()


# ---- Reading ----

def read_csv(text):
    # VRS files start with a byte-order mark.
    return list(csv.DictReader(io.StringIO(text.lstrip("﻿"), newline="")))


def clean(text):
    return " ".join(text.split())


def read_airlines(rows):
    """Airline code -> {"iata", "name"}."""
    airlines = {}
    for row in rows:
        code = row["Code"].strip()
        if not code:
            continue
        airlines[code] = {"iata": row["IATA"].strip(), "name": clean(row["Name"])}
    return airlines


def select_flights(rows):
    """(flights, stats) from route rows.

    flights: callsign code -> {number: [airport codes]}, only all-digit numbers.
    stats: what was read and dropped, for the build log.
    """
    flights = defaultdict(dict)
    stats = Counter()
    for row in rows:
        stats["rows"] += 1
        code, number = row["Code"].strip(), row["Number"].strip()
        airports = row["AirportCodes"].strip().split("-")
        if not NUMBER_RE.fullmatch(number):
            stats["dropped_not_numeric"] += 1
            continue
        if row["AirlineCode"].strip() != code or row["Callsign"].strip() != code + number:
            stats["dropped_odd_callsign"] += 1
            continue
        if len(airports) < 2 or not all(AIRPORT_RE.fullmatch(a) for a in airports):
            stats["dropped_bad_airports"] += 1
            continue
        if int(number) in flights[code]:
            stats["dropped_duplicate"] += 1
            continue
        flights[code][int(number)] = airports
        stats["kept"] += 1
    return dict(flights), stats


# ---- Encoding ----

def varint(n, out):
    if n < 0:
        raise ValueError(f"varint of negative {n}")
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)


def string(text, out):
    data = text.encode("utf-8")
    varint(len(data), out)
    out += data


def encode(flights, airlines, date, source):
    """The file's bytes (before gzip) for [flights] (see select_flights)."""
    route_use = Counter()
    airport_use = Counter()
    for numbers in flights.values():
        for airports in numbers.values():
            route_use["-".join(airports)] += 1
            airport_use.update(airports)
    airport_list = sorted(airport_use, key=lambda a: (-airport_use[a], a))
    airport_index = {a: i for i, a in enumerate(airport_list)}
    route_list = sorted(route_use, key=lambda r: (-route_use[r], r))
    route_index = {r: i for i, r in enumerate(route_list)}
    codes = sorted(flights)

    out = bytearray(MAGIC)
    varint(FORMAT_VERSION, out)
    varint(date, out)
    string(source, out)
    varint(len(airport_list), out)
    for airport in airport_list:
        string(airport, out)
    varint(len(route_list), out)
    for route in route_list:
        stops = route.split("-")
        varint(len(stops), out)
        for stop in stops:
            varint(airport_index[stop], out)
    varint(len(codes), out)
    for code in codes:
        info = airlines.get(code, {"iata": "", "name": ""})
        string(code, out)
        string(info["iata"], out)
        string(info["name"], out)
        varint(len(flights[code]), out)
    total = 0
    for code in codes:
        previous = -1
        for number in sorted(flights[code]):
            varint(number - previous - 1, out)
            previous = number
            total += 1
    for code in codes:
        for number in sorted(flights[code]):
            varint(route_index["-".join(flights[code][number])], out)
    varint(total, out)
    return bytes(out)


def compress(raw):
    return gzip.compress(raw, compresslevel=9, mtime=0)


# ---- Decoding (for the checks and the tests; the app has its own reader) ----

class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def bytes(self, n):
        if self.pos + n > len(self.data):
            raise BuildError("file ends early")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def varint(self):
        value, shift = 0, 0
        while True:
            byte = self.bytes(1)[0]
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                return value
            shift += 7
            if shift > 63:
                raise BuildError("varint too long")

    def string(self):
        return self.bytes(self.varint()).decode("utf-8")


def decode(raw):
    """A decoded file: {"version", "date", "source", "airports", "routes", "airlines", "flights"}.

    flights maps callsign (code + number) to its list of airport codes.
    """
    r = Reader(raw)
    if r.bytes(4) != MAGIC:
        raise BuildError("not a Flyoff routes file")
    version = r.varint()
    if version != FORMAT_VERSION:
        raise BuildError(f"format version {version}, expected {FORMAT_VERSION}")
    date = r.varint()
    source = r.string()
    airports = [r.string() for _ in range(r.varint())]
    routes = []
    for _ in range(r.varint()):
        stops = r.varint()
        routes.append([airports[r.varint()] for _ in range(stops)])
    airlines = []
    for _ in range(r.varint()):
        airlines.append({"code": r.string(), "iata": r.string(), "name": r.string(), "count": r.varint()})
    callsigns = []
    for airline in airlines:
        number = -1
        for _ in range(airline["count"]):
            number += r.varint() + 1
            callsigns.append(f"{airline['code']}{number}")
    flights = {callsign: routes[r.varint()] for callsign in callsigns}
    if r.varint() != len(callsigns):
        raise BuildError("flight count check failed")
    if r.pos != len(raw):
        raise BuildError(f"{len(raw) - r.pos} stray bytes at the end")
    return {"version": version, "date": date, "source": source, "airports": airports,
            "routes": routes, "airlines": airlines, "flights": flights}


def check(decoded, compressed_size):
    """Problems with a decoded file; empty if none."""
    problems = []
    n = len(decoded["flights"])
    if not MIN_FLIGHTS <= n <= MAX_FLIGHTS:
        problems.append(f"{n} flights, expected {MIN_FLIGHTS}..{MAX_FLIGHTS}")
    if len(decoded["airlines"]) < MIN_AIRLINES:
        problems.append(f"{len(decoded['airlines'])} airlines, expected at least {MIN_AIRLINES}")
    if compressed_size > MAX_BYTES:
        problems.append(f"{compressed_size} bytes, more than {MAX_BYTES}")
    for airline in decoded["airlines"]:
        if not CALLSIGN_CODE_RE.fullmatch(airline["code"]):
            problems.append(f"bad airline code {airline['code']!r}")
        if airline["iata"] and not IATA_RE.fullmatch(airline["iata"]):
            problems.append(f"{airline['code']}: bad IATA code {airline['iata']!r}")
    for airport in decoded["airports"]:
        if not AIRPORT_RE.fullmatch(airport):
            problems.append(f"bad airport code {airport!r}")
    if not COMMIT_RE.fullmatch(decoded["source"]):
        problems.append(f"bad source commit {decoded['source']!r}")
    return problems


def manifest(compressed, date, source):
    """The update manifest for a built file (what the app checks before and after downloading)."""
    return {
        "format_version": FORMAT_VERSION,
        "date": datetime.fromtimestamp(date, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "date_seconds": date,
        "file": f"{RELEASE_NAME}.bin",
        "size": len(compressed),
        "sha256": hashlib.sha256(compressed).hexdigest(),
        "source": f"{VRS_REPO}/commit/{source}",
    }


# ---- Main ----

def write_atomically(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def build(cache, latest, out_dir):
    commit = latest_commit() if latest else VRS_COMMIT
    repo = fetch(cache, commit)
    paths = input_files(repo)
    digest = content_sha256(repo, paths)
    if not latest and digest != VRS_CONTENT_SHA256:
        raise BuildError(f"input sha256 {digest}, pinned {VRS_CONTENT_SHA256}. Review the data and update the pin.")

    rows = []
    for path in paths[:-1]:
        with open(os.path.join(repo, path), encoding="utf-8", newline="") as f:
            rows += read_csv(f.read())
    with open(os.path.join(repo, AIRLINES_CSV), encoding="utf-8", newline="") as f:
        airlines = read_airlines(read_csv(f.read()))
    flights, stats = select_flights(rows)
    unknown = sorted(code for code in flights if code not in airlines)
    if unknown:
        raise BuildError(f"route codes missing from airlines.csv: {unknown[:10]}")

    date = commit_time(repo)
    raw = encode(flights, airlines, date, commit)
    compressed = compress(raw)
    decoded = decode(gzip.decompress(compressed))
    problems = check(decoded, len(compressed))
    if problems:
        raise BuildError("checks failed:\n  " + "\n  ".join(problems[:20]))

    if out_dir:
        target = os.path.join(out_dir, f"{RELEASE_NAME}.bin")
        write_atomically(target, compressed)
        info = manifest(compressed, date, commit)
        write_atomically(os.path.join(out_dir, f"{RELEASE_NAME}.json"),
                         (json.dumps(info, indent=2) + "\n").encode())
    else:
        target = ASSET
        write_atomically(target, compressed)

    dropped = stats["rows"] - stats["kept"]
    print(f"Wrote {target}: VRS {commit} of "
          f"{datetime.fromtimestamp(date, timezone.utc):%Y-%m-%d %H:%M} UTC, input sha256 {digest}")
    print(f"  {stats['rows']} callsigns read; kept {stats['kept']}; dropped {dropped} "
          f"({dropped / max(stats['rows'], 1):.1%}): {stats['dropped_not_numeric']} not all-digit numbers, "
          f"{stats['dropped_odd_callsign']} odd callsigns, {stats['dropped_bad_airports']} bad airport lists, "
          f"{stats['dropped_duplicate']} duplicates")
    print(f"  {len(decoded['airlines'])} airlines, {len(decoded['airports'])} airports, "
          f"{len(decoded['routes'])} distinct routes")
    print(f"  {len(raw)} bytes raw, {len(compressed)} bytes gzip")


def main():
    default_cache = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"),
                                 "flyoff", "routes")
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cache", default=default_cache, help=f"download directory (default {default_cache})")
    parser.add_argument("--latest", action="store_true",
                        help="build VRS's newest commit instead of the pinned one (no input pin)")
    parser.add_argument("--out", help=f"write {RELEASE_NAME}.bin and {RELEASE_NAME}.json here instead of the asset")
    args = parser.parse_args()
    try:
        build(os.path.abspath(args.cache), args.latest, args.out and os.path.abspath(args.out))
    except BuildError as e:
        sys.exit(f"build_routes: {e}")


if __name__ == "__main__":
    main()
