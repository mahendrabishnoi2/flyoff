# Read a remote PMTiles v3 archive's directories via HTTP range requests and
# sum tile byte sizes per zoom / bbox / flight corridor without downloading tiles.
# Usage: python3 -I pmsize.py https://build.protomaps.com/YYYYMMDD.pmtiles cache.pkl MAXZOOM [JSON config]
#   JSON config keys: bboxes {name:[w,s,e,n]}, bbox_zooms [..], routes {name:[[lat,lon],[lat,lon]]},
#   half_widths [km], corr_zooms [..], corr_minz N. Builds: https://build-metadata.protomaps.dev/builds.json
import sys, struct, gzip, math, bisect, subprocess, json, pickle, os

URL = sys.argv[1]
CACHE = sys.argv[2]
MAXZ = int(sys.argv[3])

def fetch(off, length):
    r = subprocess.run(["curl", "-sS", "-f", "-m", "300", "-r", f"{off}-{off+length-1}", URL],
                       capture_output=True, check=True)
    assert len(r.stdout) == length, (len(r.stdout), length)
    return r.stdout

def rvarint(b, i):
    s = 0; v = 0
    while True:
        c = b[i]; i += 1
        v |= (c & 0x7f) << s; s += 7
        if c < 0x80: return v, i

def parse_dir(raw):
    b = gzip.decompress(raw)
    i = 0
    n, i = rvarint(b, i)
    ids = []; last = 0
    for _ in range(n):
        d, i = rvarint(b, i); last += d; ids.append(last)
    rl = []
    for _ in range(n):
        v, i = rvarint(b, i); rl.append(v)
    ln = []
    for _ in range(n):
        v, i = rvarint(b, i); ln.append(v)
    offs = []
    for k in range(n):
        v, i = rvarint(b, i)
        if v == 0 and k > 0: offs.append(offs[-1] + ln[k-1])
        else: offs.append(v - 1)
    return list(zip(ids, rl, ln, offs))

def zbase(z): return (4**z - 1) // 3

if os.path.exists(CACHE):
    hdr, entries = pickle.load(open(CACHE, "rb"))
else:
    h = fetch(0, 127)
    assert h[:7] == b"PMTiles" and h[7] == 3
    f = struct.unpack("<QQQQQQQQQQQ", h[8:96])
    hdr = dict(root_off=f[0], root_len=f[1], meta_off=f[2], meta_len=f[3], leaf_off=f[4], leaf_len=f[5],
               data_off=f[6], data_len=f[7], addressed=f[8], entries=f[9], contents=f[10],
               internal_comp=h[97], tile_comp=h[98], tile_type=h[99], minz=h[100], maxz=h[101])
    assert hdr["internal_comp"] == 2  # gzip
    root = parse_dir(fetch(hdr["root_off"], hdr["root_len"]))
    limit = zbase(MAXZ + 1)
    entries = [e for e in root if e[1] > 0 and e[0] < limit]
    leaves = [e for e in root if e[1] == 0 and e[0] < limit]
    if leaves:
        lo = min(e[3] for e in leaves); hi = max(e[3] + e[2] for e in leaves)
        blob = fetch(hdr["leaf_off"] + lo, hi - lo)
        for e in leaves:
            sub = parse_dir(blob[e[3]-lo:e[3]-lo+e[2]])
            assert all(x[1] > 0 for x in sub), "nested leaves not handled"
            entries.extend(x for x in sub if x[0] < limit)
    entries.sort()
    pickle.dump((hdr, entries), open(CACHE, "wb"))

ids = [e[0] for e in entries]

def lookup(tid):
    k = bisect.bisect_right(ids, tid) - 1
    if k < 0: return None
    e = entries[k]
    if tid < e[0] + e[1]: return (e[3], e[2])
    return None

def tid2zxy(tid):
    z = 0
    while zbase(z + 1) <= tid: z += 1
    pos = tid - zbase(z); n = 2**z
    tx = ty = 0; s = 1; t = pos
    while s < n:
        rx = 1 & (t // 2); ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1: tx = s - 1 - tx; ty = s - 1 - ty
            tx, ty = ty, tx
        tx += s * rx; ty += s * ry
        t //= 4; s *= 2
    return z, tx, ty

def zxy2tid(z, x, y):
    n = 2**z; d = 0; s = n // 2
    while s > 0:
        rx = 1 if (x & s) > 0 else 0
        ry = 1 if (y & s) > 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1: x = s - 1 - x; y = s - 1 - y
            x, y = y, x
        s //= 2
    return zbase(z) + d

def per_zoom():
    out = {}
    for z in range(0, MAXZ + 1):
        seen = set(); tot = 0; cnt = 0
        a, b = zbase(z), zbase(z + 1)
        for e in entries:
            if e[0] >= b: break
            if e[0] + e[1] <= a: continue
            cnt += min(e[0]+e[1], b) - max(e[0], a)
            if e[3] not in seen: seen.add(e[3]); tot += e[2]
        out[z] = (cnt, tot)
    return out

def lon2x(lon, z): return int((lon + 180) / 360 * 2**z)
def lat2y(lat, z):
    lat = max(min(lat, 85.0511), -85.0511); r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1/math.cos(r)) / math.pi) / 2 * 2**z)
def tile_center(z, x, y):
    n = 2**z; lon = (x + .5) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + .5) / n))))
    return lat, lon

def size_of_tiles(tiles):
    seen = set(); tot = 0; cnt = 0
    for t in tiles:
        r = lookup(t)
        if r is None: continue
        cnt += 1
        if r[0] not in seen: seen.add(r[0]); tot += r[1]
    return cnt, tot

def bbox_tiles(z, w, s, e, n):
    for x in range(lon2x(w, z), lon2x(e, z) + 1):
        for y in range(lat2y(n, z), lat2y(s, z) + 1):
            yield zxy2tid(z, x, y)

R = 6371.0
def gc_points(a, b, step_km=10):
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    d = 2 * math.asin(math.sqrt(math.sin((la2-la1)/2)**2 + math.cos(la1)*math.cos(la2)*math.sin((lo2-lo1)/2)**2))
    k = max(2, int(d * R / step_km))
    pts = []
    for i in range(k + 1):
        f = i / k; A = math.sin((1-f)*d)/math.sin(d); B = math.sin(f*d)/math.sin(d)
        x = A*math.cos(la1)*math.cos(lo1) + B*math.cos(la2)*math.cos(lo2)
        y = A*math.cos(la1)*math.sin(lo1) + B*math.cos(la2)*math.sin(lo2)
        zz = A*math.sin(la1) + B*math.sin(la2)
        pts.append((math.degrees(math.atan2(zz, math.hypot(x, y))), math.degrees(math.atan2(y, x))))
    return pts, d * R

def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    return 2*R*math.asin(math.sqrt(math.sin((la2-la1)/2)**2 + math.cos(la1)*math.cos(la2)*math.sin((lo2-lo1)/2)**2))

def corridor_tiles(z, pts, half_km):
    tiles = set()
    for (lat, lon) in pts:
        dlat = half_km / 111.0; dlon = half_km / (111.0 * max(0.1, math.cos(math.radians(lat))))
        for x in range(lon2x(lon - dlon, z), lon2x(lon + dlon, z) + 1):
            for y in range(lat2y(lat + dlat, z), lat2y(lat - dlat, z) + 1):
                xx = x % (2**z)
                tlat, tlon = tile_center(z, xx, y); hd = 0.5 * math.hypot(40075.0 * math.cos(math.radians(tlat)) / 2**z, 40075.0 * math.cos(math.radians(tlat)) / 2**z)
                if hav((lat, lon), (tlat, tlon)) <= half_km + hd:
                    tiles.add(zxy2tid(z, xx, y))
    return tiles

if __name__ == "__main__":
    MB = 1e6
    print("header:", {k: hdr[k] for k in ("minz", "maxz", "tile_type", "tile_comp", "addressed", "contents")})
    pz = per_zoom(); cum = 0
    print("zoom  tiles  unique_bytes(MB)  cumulative(MB)")
    for z, (c, t) in pz.items():
        cum += t; print(f"z{z:<3} {c:>8} {t/MB:>10.1f} {cum/MB:>10.1f}")
    cfg = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
    for name, bb in cfg.get("bboxes", {}).items():
        for mz in cfg.get("bbox_zooms", []):
            all_t = [t for z in range(0, mz + 1) for t in bbox_tiles(z, *bb)]
            c, t = size_of_tiles(all_t)
            print(f"bbox {name} z0-{mz}: {c} tiles, {t/MB:.1f} MB")
    for name, (a, b) in cfg.get("routes", {}).items():
        pts, dist = gc_points(a, b)
        for half in cfg.get("half_widths", [175]):
            for mz in cfg.get("corr_zooms", [8]):
                lo = cfg.get("corr_minz", 6)
                all_t = set()
                for z in range(lo, mz + 1): all_t |= corridor_tiles(z, pts, half)
                c, t = size_of_tiles(all_t)
                print(f"corridor {name} ({dist:.0f} km, {2*half} km wide) z{lo}-{mz}: {c} tiles, {t/MB:.1f} MB")
