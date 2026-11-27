"""Unit tests for build_airports.py, with small inline fixtures and no network.

Run: python3 -I -m unittest discover -s tools/airports -p 'test_*.py'
"""
import itertools
import json
import os
import string
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_airports as b  # noqa: E402

CSV_HEADER = ('"id","ident","type","name","latitude_deg","longitude_deg","elevation_ft","continent",'
              '"iso_country","iso_region","municipality","scheduled_service","icao_code","iata_code",'
              '"gps_code","local_code","home_link","wikipedia_link","keywords"\n')


def csv_row(ident, kind, name, lat, lon, country, city, scheduled, icao, iata):
    return (f'1,"{ident}","{kind}","{name}",{lat},{lon},0,"EU","{country}","{country}-X","{city}",'
            f'"{scheduled}","{icao}","{iata}","{icao}","","","",""\n')


def airport(iata, size="L", runway=0, ident=None, **extra):
    a = {"iata": iata, "icao": "", "name": f"{iata} Airport", "city": "Town", "country": "GB",
         "lat": 1.0, "lon": 2.0, "size": size, "runway": runway, "ident": ident or "X" + iata, "tz": "Europe/London"}
    a.update(extra)
    return a


SQUARE = [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]]
HOLE = [[4, 4], [6, 4], [6, 6], [4, 6], [4, 4]]


class Selection(unittest.TestCase):
    def rows(self):
        return b.read_csv(CSV_HEADER
                          + csv_row("EGLL", "large_airport", "London Heathrow Airport", 51.47, -0.46, "GB", "London", "yes", "EGLL", "LHR")
                          + csv_row("EGKB", "medium_airport", "Biggin Hill", 51.33, 0.03, "GB", "London", "no", "EGKB", "BQH")
                          + csv_row("XHEL", "heliport", "Some Heliport", 50, 0, "GB", "X", "yes", "", "HEL")
                          + csv_row("XCLO", "closed", "Old Field", 50, 0, "GB", "X", "yes", "", "OLD")
                          + csv_row("XSEA", "seaplane_base", "Harbour Seaplane Base", 49.2, -123.1, "CA", "Vancouver", "yes", "", "CXH")
                          + csv_row("XNOI", "small_airport", "No IATA Strip", 1, 1, "GB", "X", "yes", "", "")
                          + csv_row("XSPC", "small_airport", "Tab\tand\nnewline  Field", 1, 1, "GB", " Multi \r\n line ", "yes", "", "TAB"))

    def test_keeps_scheduled_airports_with_iata_and_no_heliports(self):
        selected = b.select_airports(self.rows(), {"EGLL": 12802})
        self.assertEqual([a["iata"] for a in selected], ["LHR", "CXH", "TAB"])

    def test_record_fields(self):
        lhr = b.select_airports(self.rows(), {"EGLL": 12802})[0]
        self.assertEqual((lhr["icao"], lhr["name"], lhr["city"], lhr["country"], lhr["size"], lhr["runway"]),
                         ("EGLL", "London Heathrow Airport", "London", "GB", "L", 12802))
        self.assertAlmostEqual(lhr["lat"], 51.47)
        self.assertAlmostEqual(lhr["lon"], -0.46)

    def test_seaplane_base_is_other(self):
        self.assertEqual(b.select_airports(self.rows(), {})[1]["size"], "O")

    def test_tabs_and_line_breaks_become_spaces(self):
        tab = b.select_airports(self.rows(), {})[2]
        self.assertEqual(tab["name"], "Tab and newline Field")
        self.assertEqual(tab["city"], "Multi line")

    def test_longest_open_runway(self):
        runways = b.read_csv('"airport_ident","length_ft","closed"\n'
                             '"EGLL",12802,0\n"EGLL",12001,0\n"EGLL",14000,1\n"XNOL",,0\n')
        self.assertEqual(b.longest_runways(runways), {"EGLL": 12802, "XNOL": 0})


class Duplicates(unittest.TestCase):
    def test_larger_airport_keeps_the_code(self):
        kept, dropped = b.drop_duplicates([airport("ABC", "M", ident="SMALL"), airport("ABC", "L", ident="BIG")])
        self.assertEqual([a["ident"] for a in kept], ["BIG"])
        self.assertEqual([a["ident"] for a in dropped], ["SMALL"])

    def test_longer_runway_breaks_a_size_tie(self):
        kept, _ = b.drop_duplicates([airport("ABC", "M", 5000, "SHORT"), airport("ABC", "M", 9000, "LONG")])
        self.assertEqual(kept[0]["ident"], "LONG")

    def test_full_tie_fails(self):
        with self.assertRaisesRegex(b.BuildError, "IATA ABC"):
            b.drop_duplicates([airport("ABC", ident="ONE"), airport("ABC", ident="TWO")])

    def test_importance_order(self):
        airports = [airport("CCC", "M", 9000), airport("BBB", "L", 3000), airport("AAA", "L", 3000), airport("DDD", "L", 12000)]
        self.assertEqual([a["iata"] for a in sorted(airports, key=b.importance)], ["DDD", "AAA", "BBB", "CCC"])


class PointInPolygon(unittest.TestCase):
    def test_square(self):
        points = [(5, 5), (-1, 5), (11, 5), (5, -1), (5, 11), (0.001, 9.999)]
        self.assertEqual(b.points_in_polygon([SQUARE], points), {0, 5})

    def test_hole(self):
        points = [(5, 5), (2, 2), (5, 4.5), (8, 5)]
        self.assertEqual(b.points_in_polygon([SQUARE, HOLE], points), {1, 3})

    def test_concave(self):
        # A "U": the notch between x=3..7 above y=3 is outside.
        u = [[0, 0], [10, 0], [10, 10], [7, 10], [7, 3], [3, 3], [3, 10], [0, 10], [0, 0]]
        points = [(1, 8), (5, 8), (9, 8), (5, 1)]
        self.assertEqual(b.points_in_polygon([u], points), {0, 2, 3})

    def test_open_ring_and_level_points(self):
        # Rings need not repeat the first vertex; a point level with a vertex counts once.
        triangle = [[0, 0], [10, 5], [0, 10]]
        points = [(1, 5), (11, 5), (-1, 5), (5, 2.4), (5, 2.6)]
        self.assertEqual(b.points_in_polygon([triangle], points), {0, 4})

    def test_no_points_in_bounding_box(self):
        self.assertEqual(b.points_in_polygon([SQUARE], [(50, 50)]), set())


class Zones(unittest.TestCase):
    FEATURES = json.dumps({"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"tzid": "Etc/GMT"},
         "geometry": {"type": "Polygon", "coordinates": [[[-20, -20], [20, -20], [20, 20], [-20, 20], [-20, -20]]]}},
        {"type": "Feature", "properties": {"tzid": "Europe/Land"},
         "geometry": {"type": "MultiPolygon", "coordinates": [[SQUARE], [[[20, 20], [30, 20], [30, 30], [20, 30], [20, 20]]]]}},
    ]}, indent=1)

    def test_iter_features_streams_each_feature(self):
        self.assertEqual([tz for tz, _ in b.iter_features(self.FEATURES)], ["Etc/GMT", "Europe/Land"])

    def test_iter_features_rejects_other_json(self):
        with self.assertRaises(b.BuildError):
            list(b.iter_features('{"type": "Feature"}'))

    def test_zones_for_points_with_multipolygon(self):
        found = b.zones_for_points([(5, 5), (25, 25), (15, 15), (50, 50)], b.iter_features(self.FEATURES))
        self.assertEqual(found, [["Etc/GMT", "Europe/Land"], ["Europe/Land"], ["Etc/GMT"], []])

    def test_land_beats_ocean(self):
        self.assertEqual(b.pick_zone(["Etc/GMT", "Europe/Land"]), "Europe/Land")
        self.assertEqual(b.pick_zone(["Etc/GMT-1"]), "Etc/GMT-1")
        self.assertIsNone(b.pick_zone([]))

    def test_known_overlaps_and_unknown_ones(self):
        self.assertEqual(b.pick_zone(["Asia/Urumqi", "Asia/Shanghai"]), "Asia/Shanghai")
        self.assertEqual(b.pick_zone(["Europe/Moscow", "Asia/Tbilisi", "Etc/GMT-3"]), "Europe/Moscow")
        with self.assertRaisesRegex(b.BuildError, "several zones"):
            b.pick_zone(["Europe/Paris", "Europe/Berlin"])

    def test_assign_zones(self):
        airports = [airport("AAA", lon=5, lat=5), airport("BBB", lon=15, lat=15)]
        b.assign_zones(airports, b.iter_features(self.FEATURES))
        self.assertEqual([a["tz"] for a in airports], ["Europe/Land", "Etc/GMT"])


class Tsv(unittest.TestCase):
    def test_round_trip(self):
        a = airport("LHR", icao="EGLL", name="London Heathrow Airport", city="London",
                    lat=51.4706, lon=-0.461941, tz="Europe/London")
        text = b.to_tsv([a])
        self.assertEqual(text, "iata\ticao\tname\tcity\tcountry\tlat\tlon\tsize\ttz\n"
                               "LHR\tEGLL\tLondon Heathrow Airport\tLondon\tGB\t51.4706\t-0.4619\tL\tEurope/London\n")
        self.assertEqual(b.read_tsv(text)[0]["tz"], "Europe/London")

    def test_read_tsv_rejects_bad_files(self):
        good = b.to_tsv([airport("LHR")])
        for bad, message in ((good.replace("iata", "code"), "header"),
                             (good.rstrip("\n"), "line break"),
                             (good.replace("\tGB\t", "\tGB\textra\t"), "line 2: 10 fields")):
            with self.assertRaisesRegex(b.BuildError, message):
                b.read_tsv(bad)

    def test_check_finds_problems(self):
        rows = b.read_tsv(b.to_tsv([
            airport("LHR"), airport("LHR"), airport("lh1"), airport("BAD", icao="EG"),
            airport("POS", lat=91.0), airport("NTZ", tz=""), airport("SIZ", size="X"), airport("CTY", country="gbr"),
        ]))
        problems = "\n".join(b.check(rows))
        for expected in ("LHR (LHR Airport): duplicate", "bad IATA code 'lh1'", "bad ICAO code 'EG'",
                         "POS (POS Airport): position 91.0", "NTZ (NTZ Airport): bad time zone",
                         "bad size 'X'", "bad country 'gbr'", "8 airports, expected"):
            self.assertIn(expected, problems)

    def test_check_passes_good_rows(self):
        codes = ("".join(c) for c in itertools.product(string.ascii_uppercase, repeat=3))
        rows = b.read_tsv(b.to_tsv([airport(code) for code in itertools.islice(codes, b.MIN_AIRPORTS)]))
        self.assertEqual(b.check(rows), [])


if __name__ == "__main__":
    unittest.main()
