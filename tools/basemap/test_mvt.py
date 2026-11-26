"""Tests for mvt.py and pmtiles_read.id_to_zxy.

Run: python3 -I -m unittest discover -s tools/basemap -p 'test_*.py'

testdata/z0-0-0.mvt.gz is the zoom-0 tile of OpenFreeMap build
20261101_080013_pt, as stored (gzip). © OpenMapTiles © OpenStreetMap contributors.
"""
import gzip
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402
from pmtiles_read import id_to_zxy  # noqa: E402

TILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "z0-0-0.mvt.gz")


def load_tile():
    with open(TILE, "rb") as f:
        return gzip.decompress(f.read())


class RoundTrip(unittest.TestCase):
    def test_decode_encode_is_byte_identical(self):
        raw = load_tile()
        layers = mvt.decode(raw)
        self.assertEqual(mvt.encode(layers), raw)

    def test_decodes_expected_layers(self):
        names = [layer.name for layer in mvt.decode(load_tile())]
        self.assertEqual(names, ["boundary", "landcover", "place", "water", "water_name"])

    def test_varint_round_trip(self):
        for v in (0, 1, 127, 128, 300, 2 ** 32, 2 ** 63):
            self.assertEqual(mvt._varint(mvt._enc_varint(v), 0), (v, len(mvt._enc_varint(v))))


class Strip(unittest.TestCase):
    def setUp(self):
        self.layers = mvt.decode(load_tile())
        self.stripped = mvt.decode(mvt.encode(mvt.strip(self.layers, {"place", "water", "boundary"})))

    def test_keeps_only_requested_layers(self):
        self.assertEqual([layer.name for layer in self.stripped], ["boundary", "place", "water"])

    def test_original_tile_has_translations(self):
        place = next(layer for layer in self.layers if layer.name == "place")
        self.assertIn("name:de", place.keys)

    def test_drops_other_names_and_keeps_the_rest(self):
        before = {layer.name: layer for layer in self.layers}
        for layer in self.stripped:
            orig = before[layer.name]
            self.assertEqual(len(layer.features), len(orig.features))
            for new_f, old_f in zip(layer.features, orig.features):
                old = mvt.attributes(orig, old_f)
                want = {k: v for k, v in old.items() if not mvt.is_name_key(k) or k in mvt.KEEP_NAMES}
                self.assertEqual(mvt.attributes(layer, new_f), want)
                self.assertEqual((new_f.id, new_f.type, new_f.geometry), (old_f.id, old_f.type, old_f.geometry))

    def test_place_keeps_style_attributes(self):
        place = next(layer for layer in self.stripped if layer.name == "place")
        for key in ("class", "rank", "name", "name:en", "name:latin"):
            self.assertIn(key, place.keys)
        self.assertFalse([k for k in place.keys if mvt.is_name_key(k) and k not in mvt.KEEP_NAMES])

    def test_name_key_rules(self):
        for k in ("name", "name:de", "name_en", "name_int", "name:latin"):
            self.assertTrue(mvt.is_name_key(k))
        for k in ("class", "disputed_name", "rank", "names"):
            self.assertFalse(mvt.is_name_key(k))


class TileIds(unittest.TestCase):
    def test_id_to_zxy(self):
        # Values from the PMTiles v3 spec's Hilbert ordering.
        self.assertEqual(id_to_zxy(0), (0, 0, 0))
        self.assertEqual([id_to_zxy(i) for i in range(1, 5)], [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)])
        self.assertEqual(id_to_zxy(5), (2, 0, 0))
        self.assertEqual(id_to_zxy(20), (2, 3, 0))


if __name__ == "__main__":
    unittest.main()
