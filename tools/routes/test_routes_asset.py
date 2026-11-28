"""Checks on the committed asset, app/src/main/assets/routes/routes.bin.

Run: python3 -I -m unittest discover -s tools/routes -p 'test_*.py'
"""
import gzip
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_routes as b  # noqa: E402


class CommittedAsset(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(b.ASSET, "rb") as f:
            cls.compressed = f.read()
        cls.decoded = b.decode(gzip.decompress(cls.compressed))

    def test_passes_the_build_checks(self):
        self.assertEqual(b.check(self.decoded, len(self.compressed)), [])

    def test_is_the_pinned_snapshot(self):
        self.assertEqual(self.decoded["source"], b.VRS_COMMIT)

    def test_size(self):
        self.assertLess(len(self.compressed), b.MAX_BYTES)

    def test_ba117_is_heathrow_to_jfk(self):
        self.assertEqual(self.decoded["flights"]["BAW117"], ["EGLL", "KJFK"])

    def test_ba_is_british_airways(self):
        baw = next(a for a in self.decoded["airlines"] if a["code"] == "BAW")
        self.assertEqual((baw["iata"], baw["name"]), ("BA", "British Airways"))

    def test_only_all_digit_numbers(self):
        for callsign in self.decoded["flights"]:
            self.assertTrue(callsign[3:].isdigit() or callsign[2:].isdigit(), callsign)


if __name__ == "__main__":
    unittest.main()
