"""Read PMTiles v3 archives: local files (Archive) or, with parse_header and
parse_directory, directories fetched over HTTP. Standard library only.

Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
"""
import gzip
import json
import struct


def _varint(b, i):
    shift = value = 0
    while True:
        c = b[i]
        i += 1
        value |= (c & 0x7F) << shift
        shift += 7
        if c < 0x80:
            return value, i


HEADER_LEN = 127


def parse_header(d):
    """The fixed 127-byte header as a dict. Raises ValueError if it isn't PMTiles v3."""
    if len(d) < HEADER_LEN or d[:7] != b"PMTiles" or d[7] != 3:
        raise ValueError("not a PMTiles v3 archive")
    keys = ("root_off", "root_len", "meta_off", "meta_len", "leaf_off", "leaf_len",
            "data_off", "data_len", "addressed", "entries", "contents")
    h = dict(zip(keys, struct.unpack("<11Q", d[8:96])))
    h.update(internal_gzip=d[97] == 2, tile_compression=d[98], tile_gzip=d[98] == 2, tile_type=d[99],
             min_zoom=d[100], max_zoom=d[101],
             bounds=[v / 1e7 for v in struct.unpack("<4i", d[102:118])],
             center_zoom=d[118], center=[v / 1e7 for v in struct.unpack("<2i", d[119:127])])
    return h


def parse_directory(raw, compressed):
    """Directory entries (tile_id, run_length, offset, length). run_length 0 marks a leaf
    directory at offset/length within the leaf section; otherwise the entry stands for
    run_length consecutive tile ids that all have the same tile bytes."""
    b = gzip.decompress(raw) if compressed else raw
    n, i = _varint(b, 0)
    cols = []
    for _ in range(4):
        col = []
        for _ in range(n):
            v, i = _varint(b, i)
            col.append(v)
        cols.append(col)
    ids, run_lengths, lengths, offsets = cols
    out, tile_id = [], 0
    for k in range(n):
        tile_id += ids[k]
        off = offsets[k] - 1 if offsets[k] else out[-1][2] + out[-1][3]
        out.append((tile_id, run_lengths[k], off, lengths[k]))
    return out


def zoom_of(tile_id):
    z, base = 0, 0
    while tile_id >= base + 4 ** z:
        base += 4 ** z
        z += 1
    return z


def id_to_zxy(tile_id):
    """Hilbert tile id -> (z, x, y), y counted from the top (XYZ)."""
    z = zoom_of(tile_id)
    t = tile_id - (4 ** z - 1) // 3
    x = y = 0
    s = 1
    while s < (1 << z):
        rx = 1 & (t // 2)
        ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        t //= 4
        s *= 2
    return z, x, y


class Archive:
    def __init__(self, path):
        with open(path, "rb") as f:
            self.data = f.read()
        try:
            h = parse_header(self.data)
        except ValueError:
            raise ValueError(f"{path}: not a PMTiles v3 archive") from None
        self.root_off, self.root_len = h["root_off"], h["root_len"]
        self.meta_off, self.meta_len = h["meta_off"], h["meta_len"]
        self.leaf_off, self.leaf_len = h["leaf_off"], h["leaf_len"]
        self.data_off, self.data_len = h["data_off"], h["data_len"]
        self.internal_gzip = h["internal_gzip"]
        self.tile_gzip = h["tile_gzip"]
        self.tile_type = h["tile_type"]
        self.min_zoom, self.max_zoom = h["min_zoom"], h["max_zoom"]
        self.bounds = h["bounds"]
        self.center_zoom = h["center_zoom"]
        self.center = h["center"]

    def metadata(self):
        raw = self.data[self.meta_off:self.meta_off + self.meta_len]
        return json.loads(gzip.decompress(raw) if self.internal_gzip else raw)

    def entries(self):
        """All tile entries (tile_id, run_length, offset, length), sorted by tile id."""
        out, stack = [], [(self.root_off, self.root_len)]
        while stack:
            off, ln = stack.pop()
            for e in parse_directory(self.data[off:off + ln], self.internal_gzip):
                if e[1] == 0:
                    stack.append((self.leaf_off + e[2], e[3]))
                else:
                    out.append(e)
        return sorted(out)

    def tile(self, offset, length):
        """Tile bytes, decompressed."""
        raw = self.data[self.data_off + offset:self.data_off + offset + length]
        return gzip.decompress(raw) if self.tile_gzip else raw
