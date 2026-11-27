"""Build the tile size table the app uses to estimate route download sizes.

For every zoom-5 tile (1,024 of them) and each zoom 6-9, the table holds the mean and the
maximum stored size of that tile's descendants in the OpenFreeMap planet PMTiles. Only the
archive's header and directories are read, with HTTP range requests; no tiles are downloaded.

Usage: python3 -I tools/basemap/tile_size_table.py PLANET_URL BUILD OUT.bin

Sizes are the stored (compressed) tile lengths. A directory entry with run_length n stands for
n consecutive tile ids with the same bytes, and each of them counts. Tile ids absent from the
directory have no tile (the server sends nothing) and count as 0 bytes in the mean.

Format of OUT.bin, all integers big-endian:

  offset  size  field
  0       4     magic "FTSZ"
  4       1     format version, 1
  5       1     base zoom (5)
  6       1     first zoom (6)
  7       1     last zoom (9)
  8       1     tile compression from the PMTiles header (2 = gzip)
  9       2     unit in bytes (64)
  11      1     length n of the build name
  12      n     build name, ASCII (e.g. 20261101_080013_pt)
  12+n    ...   for each zoom from first to last, for row y in 0..2^base-1, for column x in
                0..2^base-1: mean (u16) then max (u16) stored size of the descendants of
                base-zoom tile (x, y) at that zoom, in units, rounded up.

With the defaults the body is 4 zooms x 1,024 tiles x 4 bytes = 16 KiB.
"""
import math
import os
import struct
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pmtiles_read import HEADER_LEN, id_to_zxy, parse_directory, parse_header  # noqa: E402

MAGIC = b"FTSZ"
VERSION = 1
BASE_ZOOM = 5
MIN_ZOOM = 6
MAX_ZOOM = 9
UNIT = 64
USER_AGENT = "Flyoff-build (+https://github.com/mahendrabishnoi2/flyoff)"


def zoom_base(z):
    """The first Hilbert tile id at zoom z."""
    return (4 ** z - 1) // 3


class RangeReader:
    """Reads byte ranges of a remote file and counts the bytes downloaded."""

    def __init__(self, url):
        self.url = url
        self.bytes_read = 0
        self.requests = 0

    def __call__(self, offset, length):
        req = urllib.request.Request(self.url, headers={
            "User-Agent": USER_AGENT, "Range": f"bytes={offset}-{offset + length - 1}"})
        with urllib.request.urlopen(req, timeout=300) as r:
            if r.status != 206:
                raise IOError(f"expected 206 Partial Content, got {r.status}")
            data = r.read()
        if len(data) != length:
            raise IOError(f"asked for {length} bytes at {offset}, got {len(data)}")
        self.bytes_read += length
        self.requests += 1
        return data


def tile_entries(read, header, min_id, max_id):
    """Tile entries (tile_id, run_length, offset, length) whose ids overlap [min_id, max_id),
    sorted, reading only the leaf directories that can hold such ids."""
    root = parse_directory(read(header["root_off"], header["root_len"]), header["internal_gzip"])
    return sorted(_walk(read, header, root, min_id, max_id))


def _walk(read, header, entries, min_id, max_id):
    out, leaves = [], []
    for k, (tile_id, run, offset, length) in enumerate(entries):
        if run > 0:
            if tile_id < max_id and tile_id + run > min_id:
                out.append((tile_id, run, offset, length))
        else:
            # A leaf holds ids from its own up to the next entry's.
            end = entries[k + 1][0] if k + 1 < len(entries) else math.inf
            if tile_id < max_id and end > min_id:
                leaves.append((offset, length))
    if leaves:
        # Leaves are stored in tile-id order, so the ones needed are one contiguous range.
        lo = min(o for o, _ in leaves)
        hi = max(o + n for o, n in leaves)
        blob = read(header["leaf_off"] + lo, hi - lo)
        for o, n in leaves:
            sub = parse_directory(blob[o - lo:o - lo + n], header["internal_gzip"])
            out += _walk(read, header, sub, min_id, max_id)
    return out


def aggregate(entries, base_zoom=BASE_ZOOM, min_zoom=MIN_ZOOM, max_zoom=MAX_ZOOM):
    """{zoom: (means, maxes, present)}: per base-zoom tile (index y * 2^base + x), the mean and
    max stored length of its descendants at that zoom (absent tiles count as 0), and the number
    of tiles present at each zoom."""
    side = 1 << base_zoom
    sums = {z: [0] * (side * side) for z in range(min_zoom, max_zoom + 1)}
    maxes = {z: [0] * (side * side) for z in range(min_zoom, max_zoom + 1)}
    present = {z: 0 for z in range(min_zoom, max_zoom + 1)}
    lo, hi = zoom_base(min_zoom), zoom_base(max_zoom + 1)
    for tile_id, run, _offset, length in entries:
        for t in range(max(tile_id, lo), min(tile_id + run, hi)):
            z, x, y = id_to_zxy(t)
            shift = z - base_zoom
            i = (y >> shift) * side + (x >> shift)
            sums[z][i] += length
            if length > maxes[z][i]:
                maxes[z][i] = length
            present[z] += 1
    out = {}
    for z in range(min_zoom, max_zoom + 1):
        children = 4 ** (z - base_zoom)
        out[z] = ([s / children for s in sums[z]], maxes[z], present[z])
    return out


def units(value):
    """value bytes in UNIT units, rounded up; fails if it doesn't fit a u16."""
    u = math.ceil(value / UNIT)
    if u > 0xFFFF:
        raise ValueError(f"{value} bytes does not fit the table")
    return u


def encode(stats, build, tile_compression, base_zoom=BASE_ZOOM):
    zooms = sorted(stats)
    name = build.encode("ascii")
    out = bytearray(MAGIC)
    out += struct.pack(">BBBBBHB", VERSION, base_zoom, zooms[0], zooms[-1], tile_compression, UNIT, len(name))
    out += name
    for z in zooms:
        means, maxes = stats[z][0], stats[z][1]
        for i in range(len(means)):
            out += struct.pack(">HH", units(means[i]), units(maxes[i]))
    return bytes(out)


def decode(data):
    """(build, tile_compression, unit, {zoom: [(mean, max) in bytes per base tile]}); the
    inverse of encode, up to the rounding."""
    if data[:4] != MAGIC:
        raise ValueError("not a tile size table")
    version, base, first, last, compression, unit, n = struct.unpack(">BBBBBHB", data[4:12])
    if version != VERSION:
        raise ValueError(f"unknown version {version}")
    build = data[12:12 + n].decode("ascii")
    pos = 12 + n
    side = 1 << base
    table = {}
    for z in range(first, last + 1):
        cells = []
        for _ in range(side * side):
            mean, mx = struct.unpack(">HH", data[pos:pos + 4])
            cells.append((mean * unit, mx * unit))
            pos += 4
        table[z] = cells
    if pos != len(data):
        raise ValueError("trailing bytes")
    return build, compression, unit, table


def main(argv):
    if len(argv) != 4:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    url, build, out_path = argv[1:]
    read = RangeReader(url)
    header = parse_header(read(0, HEADER_LEN))
    entries = tile_entries(read, header, zoom_base(MIN_ZOOM), zoom_base(MAX_ZOOM + 1))
    stats = aggregate(entries)
    data = encode(stats, build, header["tile_compression"])
    with open(out_path, "wb") as f:
        f.write(data)
    print(f"Read {read.bytes_read:,} bytes of directories in {read.requests} requests; "
          f"wrote {len(data):,} bytes to {out_path}")
    for z, (means, maxes, present) in sorted(stats.items()):
        print(f"  z{z}: {present:,} of {4 ** z:,} tiles present, mean {sum(means) / len(means) / 1e3:.1f} KB,"
              f" largest base-tile mean {max(means) / 1e3:.1f} KB, largest tile {max(maxes) / 1e3:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
