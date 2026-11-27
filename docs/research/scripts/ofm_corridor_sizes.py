"""Measure OpenFreeMap vector tile sizes inside Flyoff's flight corridors.

Usage: python3 -I ofm_corridor_sizes.py [--seed N]

For each route below it counts every web-mercator tile at zooms 6-9 that has any part within
175 km of the great-circle route (an independent check of the app's own tile cover), then
downloads a random sample of at most 40 tiles per zoom per route (480 tiles at most in all)
from the public OpenFreeMap server, with at most 4 parallel requests and a User-Agent naming
Flyoff. It prints, per route and zoom, the full tile count, the sample mean, p90 and max tile
size, both as sent over the wire (gzip) and decompressed, and the extrapolated corridor total
(full count x sample mean) for zooms 6-8 and for zoom 9.

Standard library only. Tiles are not saved to disk.
"""
import argparse
import concurrent.futures
import gzip
import json
import math
import random
import statistics
import sys
import urllib.request

TILEJSON = "https://tiles.openfreemap.org/planet"
USER_AGENT = "Flyoff-research/0.1 (+https://github.com/mahendrabishnoi2/flyoff)"
PARALLEL = 4
SAMPLE_PER_ZOOM = 40
ZOOMS = (6, 7, 8, 9)
R = 6_371_008.8
HALF_WIDTH_M = 175_000.0
MAX_LAT = 85.05112877980659

# OurAirports coordinates (public domain), as in app/src/main/assets/airports/airports.tsv.
ROUTES = {
    "LHR-JFK": ((51.4707, -0.4599), (40.6394, -73.7793)),
    "FRA-DEL": ((50.0267, 8.5584), (28.5556, 77.0952)),
    "SYD-LAX": ((-33.9461, 151.1770), (33.9425, -118.4080)),
}


# --- spherical geometry on unit vectors ---

def vec(lat, lon):
    la, lo = math.radians(lat), math.radians(lon)
    return (math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la))


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def norm(a):
    return math.sqrt(dot(a, a))


def unit(a):
    n = norm(a)
    return (a[0] / n, a[1] / n, a[2] / n)


def angle(a, b):
    return math.atan2(norm(cross(a, b)), dot(a, b))


def to_latlon(v):
    return math.degrees(math.atan2(v[2], math.hypot(v[0], v[1]))), math.degrees(math.atan2(v[1], v[0]))


class Route:
    def __init__(self, a, b):
        self.a, self.b = vec(*a), vec(*b)
        self.n = unit(cross(self.a, self.b))
        self.length = angle(self.a, self.b) * R

    def points(self, step_m):
        k = max(1, math.ceil(self.length / step_m))
        d = self.length / R
        out = []
        for i in range(k + 1):
            f = i / k
            wa, wb = math.sin((1 - f) * d) / math.sin(d), math.sin(f * d) / math.sin(d)
            out.append(to_latlon(tuple(wa * x + wb * y for x, y in zip(self.a, self.b))))
        return out

    def distance(self, p):
        """Distance in metres from unit vector p to the route segment."""
        s = dot(p, self.n)
        foot = (p[0] - s * self.n[0], p[1] - s * self.n[1], p[2] - s * self.n[2])
        if norm(foot) > 1e-12 and dot(cross(self.a, foot), self.n) >= 0 and dot(cross(foot, self.b), self.n) >= 0:
            return abs(math.asin(max(-1.0, min(1.0, s)))) * R
        return min(angle(p, self.a), angle(p, self.b)) * R


# --- web mercator tiles ---

def merc_y(lat):
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    r = math.radians(lat)
    return (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2


def tile_x(lon, z):
    n = 2 ** z
    return min(n - 1, int((lon + 180) / 360 * n))


def tile_y(lat, z):
    n = 2 ** z
    return min(n - 1, max(0, int(merc_y(lat) * n)))


def lat_of_y(y, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / 2 ** z))))


def tile_border(z, x, y, per_edge=16):
    """Points along the tile's four edges, corners included, per_edge + 1 per edge."""
    n = 2 ** z
    west, east = x / n * 360 - 180, (x + 1) / n * 360 - 180
    north, south = lat_of_y(y, z), lat_of_y(y + 1, z)
    pts = []
    for i in range(per_edge + 1):
        f = i / per_edge
        lon = west + f * (east - west)
        lat = lat_of_y(y + f, z)
        pts += [(north, lon), (south, lon), (lat, west), (lat, east)]
    return pts


def corridor_tiles(route, z, half_width):
    """Every tile at zoom z with any part within half_width of the route (border sampled)."""
    n = 2 ** z
    samples = route.points(10_000.0)
    on_route = {(tile_x(lon, z), tile_y(lat, z)) for lat, lon in route.points(2_000.0)}
    candidates = set()
    dlat = math.degrees(half_width / R) + 0.5
    for lat, lon in samples:
        top = min(MAX_LAT, lat + dlat)
        bottom = max(-MAX_LAT, lat - dlat)
        cos_max = math.cos(math.radians(max(abs(top), abs(bottom))))
        dlon = min(180.0, math.degrees(half_width / R) / max(cos_max, 1e-6) + 0.5)
        for xx in range(int(math.floor((lon - dlon + 180) / 360 * n)), int(math.floor((lon + dlon + 180) / 360 * n)) + 1):
            for yy in range(tile_y(top, z), tile_y(bottom, z) + 1):
                candidates.add((xx % n, yy))
    tiles = set()
    for x, y in candidates:
        if (x, y) in on_route or any(route.distance(vec(la, lo)) <= half_width for la, lo in tile_border(z, x, y)):
            tiles.add((x, y))
    return tiles


# --- download ---

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
        encoding = r.headers.get("Content-Encoding", "")
    raw = gzip.decompress(body) if encoding == "gzip" or body[:2] == b"\x1f\x8b" else body
    return len(body), len(raw)


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, max(0, math.ceil(p / 100 * len(s)) - 1))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=20261127)
    parser.add_argument("--count-only", action="store_true", help="print tile counts, download nothing")
    args = parser.parse_args()
    rng = random.Random(args.seed)

    req = urllib.request.Request(TILEJSON, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        template = json.load(r)["tiles"][0]
    print(f"tile template: {template}")

    plan = []  # (route, z, full_count, sampled tiles)
    for name, (a, b) in ROUTES.items():
        route = Route(a, b)
        for z in ZOOMS:
            tiles = sorted(corridor_tiles(route, z, HALF_WIDTH_M))
            sample = tiles if len(tiles) <= SAMPLE_PER_ZOOM else rng.sample(tiles, SAMPLE_PER_ZOOM)
            plan.append((name, route.length, z, len(tiles), sample))
    if args.count_only:
        for name, length, z, count, _ in plan:
            print(f"{name} {length / 1000:.0f} km z{z}: {count} tiles")
        return 0
    total = sum(len(s) for *_, s in plan)
    assert total <= 480, total
    print(f"downloading {total} tiles, {PARALLEL} at a time")

    urls = {(z, x, y): template.format(z=z, x=x, y=y) for _, _, z, _, s in plan for x, y in s}
    with concurrent.futures.ThreadPoolExecutor(max_workers=PARALLEL) as pool:
        sizes = dict(zip(urls, pool.map(fetch, urls.values())))

    all_by_zoom = {z: [] for z in ZOOMS}
    print("\nroute    length   z  tiles  n | wire KB: mean   p90    max | raw KB: mean   p90    max | est. wire MB")
    for name, length, z, count, sample in plan:
        wire = [sizes[(z, x, y)][0] for x, y in sample]
        raw = [sizes[(z, x, y)][1] for x, y in sample]
        all_by_zoom[z] += wire
        print(f"{name}  {length / 1000:6.0f}  {z}  {count:5d} {len(sample):2d} |"
              f" {statistics.mean(wire) / 1e3:6.1f} {pct(wire, 90) / 1e3:6.1f} {max(wire) / 1e3:6.1f} |"
              f" {statistics.mean(raw) / 1e3:6.1f} {pct(raw, 90) / 1e3:6.1f} {max(raw) / 1e3:6.1f} |"
              f" {count * statistics.mean(wire) / 1e6:6.1f}")

    print("\nextrapolated corridor totals (full tile count x sample mean), wire / raw MB:")
    for name in ROUTES:
        rows = [(z, c, s) for n_, _, z, c, s in plan if n_ == name]
        for label, zs in (("z6-8", (6, 7, 8)), ("z9", (9,))):
            tiles = sum(c for z, c, _ in rows if z in zs)
            w = sum(c * statistics.mean(sizes[(z, x, y)][0] for x, y in s) for z, c, s in rows if z in zs)
            r = sum(c * statistics.mean(sizes[(z, x, y)][1] for x, y in s) for z, c, s in rows if z in zs)
            print(f"  {name} {label}: {tiles} tiles, {w / 1e6:.1f} MB wire, {r / 1e6:.1f} MB raw")

    print("\nall routes pooled, wire KB per tile:")
    for z in ZOOMS:
        v = all_by_zoom[z]
        print(f"  z{z}: n={len(v)} mean {statistics.mean(v) / 1e3:.1f}  p75 {pct(v, 75) / 1e3:.1f}"
              f"  p90 {pct(v, 90) / 1e3:.1f}  max {max(v) / 1e3:.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
