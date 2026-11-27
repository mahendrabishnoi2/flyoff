"""Checks on the committed asset, app/src/main/assets/airports/airports.tsv.

Run: python3 -I -m unittest discover -s tools/airports -p 'test_*.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_airports as b  # noqa: E402


class CommittedAsset(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(b.ASSET, encoding="utf-8", newline="") as f:
            cls.text = f.read()
        cls.rows = b.read_tsv(cls.text)
        cls.by_iata = {row["iata"]: row for row in cls.rows}

    def test_passes_the_build_checks(self):
        self.assertEqual(b.check(self.rows), [])

    def test_row_count(self):
        self.assertTrue(4000 <= len(self.rows) <= 4500, len(self.rows))

    def test_no_carriage_returns(self):
        self.assertNotIn("\r", self.text)

    def test_time_zones(self):
        expected = {
            "LHR": "Europe/London", "JFK": "America/New_York", "SYD": "Australia/Sydney",
            "DEL": "Asia/Kolkata", "PHX": "America/Phoenix", "HNL": "Pacific/Honolulu",
            # Islands, reclaimed land and runways on the shore.
            "MLE": "Indian/Maldives", "HKG": "Asia/Hong_Kong", "KIX": "Asia/Tokyo", "FAE": "Atlantic/Faroe",
            "PPT": "Pacific/Tahiti", "GIB": "Europe/Gibraltar", "MRU": "Indian/Mauritius",
            "SIN": "Asia/Singapore", "LPA": "Atlantic/Canary", "PDL": "Atlantic/Azores", "NAN": "Pacific/Fiji",
            # Overlapping zones resolved in build_airports.OVERLAPS.
            "URC": "Asia/Shanghai", "SUI": "Europe/Moscow",
        }
        self.assertEqual({code: self.by_iata[code]["tz"] for code in expected}, expected)

    def test_no_airport_has_only_an_ocean_zone(self):
        self.assertEqual([r["iata"] for r in self.rows if r["tz"].startswith("Etc/")], [])

    def test_lookups(self):
        lhr = self.by_iata["LHR"]
        self.assertEqual((lhr["icao"], lhr["name"], lhr["city"], lhr["country"], lhr["size"]),
                         ("EGLL", "London Heathrow Airport", "London", "GB", "L"))
        self.assertAlmostEqual(float(lhr["lat"]), 51.47, places=1)
        self.assertAlmostEqual(float(lhr["lon"]), -0.46, places=1)

    def test_keywords(self):
        self.assertIn("New York City", self.by_iata["EWR"]["keywords"].split(","))
        self.assertIn("Tokyo", self.by_iata["NRT"]["keywords"].split(","))
        self.assertIn("LON", self.by_iata["LHR"]["keywords"].split(","))
        self.assertIn("Bombay", self.by_iata["BOM"]["keywords"].split(","))

    def test_heathrow_is_listed_before_gatwick(self):
        order = [r["iata"] for r in self.rows]
        self.assertLess(order.index("LHR"), order.index("LGW"))


if __name__ == "__main__":
    unittest.main()
