"""Tests for tile_size_table.py and the committed app/src/main/assets/basemap/tile_sizes.bin.

Run: python3 -I -m unittest discover -s tools/basemap -p 'test_*.py'
"""
import gzip
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tile_size_table as tst  # noqa: E402
from pmtiles_read import parse_directory  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "basemap", "tile_sizes.bin")


def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def directory(entries):
    """A gzip PMTiles v3 directory for (tile_id, run_length, offset, length) entries."""
    b = bytearray(varint(len(entries)))
    last = 0
    for e in entries:
        b += varint(e[0] - last)
        last = e[0]
    for e in entries:
        b += varint(e[1])
    for e in entries:
        b += varint(e[3])
    for e in entries:
        b += varint(e[2] + 1)
    return gzip.compress(bytes(b))


def zxy_to_id(z, x, y):
    n, d, s = 1 << z, 0, (1 << z) // 2
    while s > 0:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x, y = n - 1 - x, n - 1 - y
            x, y = y, x
        s //= 2
    return tst.zoom_base(z) + d


class FakeArchive:
    """An in-memory archive: a root directory pointing at three leaves, reads recorded."""

    def __init__(self):
        leaf_a = directory([(0, 1, 0, 10), (1, 4, 10, 20)])                       # zooms 0-1
        leaf_b = directory([(tst.zoom_base(6), 4096, 30, 100)])                     # all of zoom 6
        leaf_c = directory([(tst.zoom_base(10), 1, 130, 5)])                        # zoom 10
        self.leaves = leaf_a + leaf_b + leaf_c
        root = directory([
            (0, 0, 0, len(leaf_a)),
            (tst.zoom_base(6), 0, len(leaf_a), len(leaf_b)),
            (tst.zoom_base(10), 0, len(leaf_a) + len(leaf_b), len(leaf_c)),
        ])
        self.header = {"root_off": 127, "root_len": len(root), "leaf_off": 127 + len(root), "internal_gzip": True}
        self.data = bytes(127) + root + self.leaves
        self.reads = []

    def __call__(self, offset, length):
        self.reads.append((offset, length))
        return self.data[offset:offset + length]


class Directories(unittest.TestCase):
    def test_round_trip_of_the_test_encoder(self):
        entries = [(5, 1, 0, 7), (6, 3, 7, 9), (20, 1, 0, 7)]
        self.assertEqual(parse_directory(directory(entries), True), entries)

    def test_reads_only_the_leaves_that_can_hold_the_zooms(self):
        fake = FakeArchive()
        entries = tst.tile_entries(fake, fake.header, tst.zoom_base(6), tst.zoom_base(10))
        self.assertEqual(entries, [(tst.zoom_base(6), 4096, 30, 100)])
        # The root, then one range holding only leaf b (leaf a ends before zoom 6, leaf c starts at 10).
        self.assertEqual(len(fake.reads), 2)
        leaf_b_len = fake.reads[1][1]
        self.assertLess(leaf_b_len, len(fake.leaves))


class Aggregate(unittest.TestCase):
    def test_runs_count_every_tile_and_absent_tiles_count_as_zero(self):
        # Zoom 6 tiles under base tile (0, 0): (0,0) (1,0) (0,1) (1,1).
        ids = sorted(zxy_to_id(6, x, y) for x, y in ((0, 0), (1, 0), (0, 1), (1, 1)))
        self.assertEqual(ids, list(range(ids[0], ids[0] + 4)))  # consecutive on the Hilbert curve
        entries = [(ids[0], 3, 0, 300), (ids[3], 1, 300, 1000)]
        stats = tst.aggregate(entries, base_zoom=5, min_zoom=6, max_zoom=6)
        means, maxes, present = stats[6]
        self.assertEqual(means[0], (3 * 300 + 1000) / 4)
        self.assertEqual(maxes[0], 1000)
        self.assertEqual(present, 4)
        self.assertEqual(sum(means[1:]), 0)

    def test_missing_tiles_lower_the_mean(self):
        one = zxy_to_id(7, 63, 1)  # base (15, 0) has 16 zoom-7 descendants
        means, maxes, present = tst.aggregate([(one, 1, 0, 1600)], 5, 7, 7)[7]
        self.assertEqual(means[0 * 32 + 15], 100)
        self.assertEqual(maxes[15], 1600)
        self.assertEqual(present, 1)

    def test_entries_outside_the_zooms_are_ignored(self):
        entries = [(tst.zoom_base(5), 10, 0, 999), (tst.zoom_base(10), 10, 0, 999)]
        stats = tst.aggregate(entries, 5, 6, 9)
        self.assertTrue(all(sum(stats[z][0]) == 0 for z in stats))


class Encoding(unittest.TestCase):
    def test_round_trip_rounds_up_to_the_unit(self):
        stats = {6: ([0.0, 1.0, 64.0, 65.0], [0, 64, 128, 129], 4)}
        data = tst.encode(stats, "testbuild", 2, base_zoom=1)
        build, compression, unit, table = tst.decode(data)
        self.assertEqual((build, compression, unit), ("testbuild", 2, 64))
        self.assertEqual(table[6], [(0, 0), (64, 64), (64, 128), (128, 192)])

    def test_values_too_large_are_refused(self):
        with self.assertRaises(ValueError):
            tst.encode({6: ([0.0, 0.0, 0.0, 64.0 * 70000], [0, 0, 0, 0], 1)}, "b", 2, base_zoom=1)

    def test_garbage_is_refused(self):
        with self.assertRaises(ValueError):
            tst.decode(b"PMTiles...")


class CommittedTable(unittest.TestCase):
    def setUp(self):
        with open(ASSET, "rb") as f:
            self.data = f.read()
        self.build, self.compression, self.unit, self.table = tst.decode(self.data)

    def test_matches_the_pinned_build(self):
        with open(os.path.join(HERE, "build_basemap.sh")) as f:
            pinned = re.search(r'^OFM_BUILD="([^"]+)"', f.read(), re.M).group(1)
        self.assertEqual(self.build, pinned)
        self.assertEqual(self.compression, 2)  # gzip

    def test_shape_and_size(self):
        self.assertEqual(sorted(self.table), [6, 7, 8, 9])
        self.assertTrue(all(len(cells) == 1024 for cells in self.table.values()))
        self.assertLess(len(self.data), 20_000)

    def test_values_are_plausible(self):
        for z, cells in self.table.items():
            for mean, mx in cells:
                self.assertLessEqual(mean, mx + self.unit)
        # Base tile (15, 10) holds London; (3, 15) is open Pacific south-east of Hawaii.
        london = self.table[8][10 * 32 + 15][0]
        pacific = self.table[8][15 * 32 + 3][0]
        self.assertGreater(london, 20_000)
        self.assertLess(pacific, 2_000)


if __name__ == "__main__":
    unittest.main()
