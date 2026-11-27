"""Unit tests for build_routes.py, with small inline fixtures and no network.

Run: python3 -I -m unittest discover -s tools/routes -p 'test_*.py'
"""
import hashlib
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_routes as b  # noqa: E402

ROUTES_CSV = (
    "﻿Callsign,Code,Number,AirlineCode,AirportCodes\n"
    "BAW117,BAW,117,BAW,EGLL-KJFK\n"
    "BAW1,BAW,1,BAW,EGLL-KJFK\n"
    "BAW15,BAW,15,BAW,EGLL-WSSS-YSSY\n"
    "BAW12A,BAW,12A,BAW,EGLL-EDDF\n"
    "SHT1,SHT,1,SHT,EGLL-EGPH\n"
    "EZY12AB,EZY,12AB,EZY,EGKK-LFPG\n"
    "EZY1234,EZY,1234,EZY,EGKK-LEPA\n"
)
AIRLINES_CSV = (
    "﻿Code,Name,ICAO,IATA,PositioningFlightPattern,CharterFlightPattern\n"
    "BAW,British Airways,BAW,BA,,\n"
    "SHT,BA  Shuttle,SHT,BA,,\n"
    "EZY,easyJet,EZY,U2,,\n"
    "1B,Abacus International,,1B,,\n"
)
COMMIT = "bd5f5d5af341ed9d75e1758e4a6bb740e8247f93"


def fixture():
    flights, stats = b.select_flights(b.read_csv(ROUTES_CSV))
    airlines = b.read_airlines(b.read_csv(AIRLINES_CSV))
    return flights, stats, airlines


class Varints(unittest.TestCase):
    def test_round_trip(self):
        for n in (0, 1, 127, 128, 300, 16383, 16384, 9999, 2**31, 2**40):
            out = bytearray()
            b.varint(n, out)
            self.assertEqual(b.Reader(bytes(out)).varint(), n)

    def test_small_numbers_take_one_byte(self):
        out = bytearray()
        b.varint(127, out)
        self.assertEqual(bytes(out), b"\x7f")
        out = bytearray()
        b.varint(128, out)
        self.assertEqual(bytes(out), b"\x80\x01")

    def test_negative_is_refused(self):
        with self.assertRaises(ValueError):
            b.varint(-1, bytearray())

    def test_strings_are_utf8_with_a_length(self):
        out = bytearray()
        b.string("Zürich", out)
        self.assertEqual(out[0], len("Zürich".encode()))
        self.assertEqual(b.Reader(bytes(out)).string(), "Zürich")


class Selection(unittest.TestCase):
    def test_keeps_only_all_digit_numbers(self):
        flights, stats, _ = fixture()
        self.assertEqual(sorted(flights["BAW"]), [1, 15, 117])
        self.assertEqual(sorted(flights["EZY"]), [1234])
        self.assertEqual(stats["rows"], 7)
        self.assertEqual(stats["kept"], 5)
        self.assertEqual(stats["dropped_not_numeric"], 2)

    def test_keeps_every_stop_in_order(self):
        flights, _, _ = fixture()
        self.assertEqual(flights["BAW"][15], ["EGLL", "WSSS", "YSSY"])

    def test_drops_rows_whose_callsign_is_not_code_and_number(self):
        flights, stats = b.select_flights(b.read_csv(
            "Callsign,Code,Number,AirlineCode,AirportCodes\nBAW9,BAW,8,BAW,EGLL-KJFK\nXYZ1,XYZ,1,ABC,EGLL-KJFK\n"))
        self.assertEqual(flights, {})
        self.assertEqual(stats["dropped_odd_callsign"], 2)

    def test_drops_bad_airport_lists(self):
        flights, stats = b.select_flights(b.read_csv(
            "Callsign,Code,Number,AirlineCode,AirportCodes\nBAW9,BAW,9,BAW,EGLL\nBAW8,BAW,8,BAW,EGLL-k jfk\n"))
        self.assertEqual(flights, {})
        self.assertEqual(stats["dropped_bad_airports"], 2)

    def test_airlines_keep_iata_and_a_clean_name(self):
        airlines = b.read_airlines(b.read_csv(AIRLINES_CSV))
        self.assertEqual(airlines["SHT"], {"iata": "BA", "name": "BA Shuttle"})
        self.assertEqual(airlines["1B"]["iata"], "1B")


class Encoding(unittest.TestCase):
    def encoded(self):
        flights, _, airlines = fixture()
        return b.encode(flights, airlines, 1795754966, COMMIT)

    def test_round_trip(self):
        decoded = b.decode(self.encoded())
        self.assertEqual(decoded["version"], b.FORMAT_VERSION)
        self.assertEqual(decoded["date"], 1795754966)
        self.assertEqual(decoded["source"], COMMIT)
        self.assertEqual(decoded["flights"], {
            "BAW1": ["EGLL", "KJFK"], "BAW15": ["EGLL", "WSSS", "YSSY"], "BAW117": ["EGLL", "KJFK"],
            "EZY1234": ["EGKK", "LEPA"], "SHT1": ["EGLL", "EGPH"],
        })

    def test_airlines_carry_iata_and_name_sorted_by_code(self):
        airlines = b.decode(self.encoded())["airlines"]
        self.assertEqual([(a["code"], a["iata"], a["name"], a["count"]) for a in airlines], [
            ("BAW", "BA", "British Airways", 3), ("EZY", "U2", "easyJet", 1), ("SHT", "BA", "BA Shuttle", 1),
        ])

    def test_common_airports_and_routes_come_first(self):
        decoded = b.decode(self.encoded())
        self.assertEqual(decoded["airports"][0], "EGLL")
        self.assertEqual(decoded["routes"][0], ["EGLL", "KJFK"])

    def test_starts_with_magic_and_version(self):
        self.assertEqual(self.encoded()[:5], b"FLYR\x01")

    def test_same_input_same_bytes(self):
        self.assertEqual(b.compress(self.encoded()), b.compress(self.encoded()))

    def test_gzip_has_no_timestamp(self):
        self.assertEqual(b.compress(self.encoded())[4:8], b"\0\0\0\0")

    def test_decode_refuses_damage(self):
        raw = self.encoded()
        with self.assertRaises(b.BuildError):
            b.decode(b"NOPE" + raw[4:])
        with self.assertRaises(b.BuildError):
            b.decode(raw[:4] + b"\x02" + raw[5:])
        with self.assertRaises(b.BuildError):
            b.decode(raw[:-3])
        with self.assertRaises(b.BuildError):
            b.decode(raw + b"\0")


class Checks(unittest.TestCase):
    def test_small_fixture_fails_the_count_checks(self):
        flights, _, airlines = fixture()
        decoded = b.decode(b.encode(flights, airlines, 0, COMMIT))
        problems = b.check(decoded, 1000)
        self.assertTrue(any("flights" in p for p in problems), problems)
        self.assertTrue(any("airlines" in p for p in problems), problems)

    def test_bad_codes_and_size(self):
        decoded = {"flights": {}, "airlines": [{"code": "bA", "iata": "BAA"}], "airports": ["EG LL"], "source": "x"}
        problems = b.check(decoded, b.MAX_BYTES + 1)
        for want in ("bytes", "bad airline code", "bad IATA code", "bad airport code", "bad source commit"):
            self.assertTrue(any(want in p for p in problems), (want, problems))


class Manifest(unittest.TestCase):
    def test_fields(self):
        data = b"some bytes"
        info = b.manifest(data, 1795754966, COMMIT)
        self.assertEqual(info, {
            "format_version": 1,
            "date": "2026-11-27T04:49:26Z",
            "date_seconds": 1795754966,
            "file": "routes-v1.bin",
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "source": f"https://github.com/vradarserver/standing-data/commit/{COMMIT}",
        })


class Inputs(unittest.TestCase):
    def tree(self, files):
        root = tempfile.mkdtemp()
        for path, text in files.items():
            os.makedirs(os.path.dirname(os.path.join(root, path)), exist_ok=True)
            with open(os.path.join(root, path), "w", encoding="utf-8") as f:
                f.write(text)
        return root

    def test_finds_the_route_files_and_airlines(self):
        root = self.tree({"routes/schema-01/B/BAW-all.csv": "x", "routes/schema-01/E/EZY-1.csv": "y",
                          "airlines/schema-01/airlines.csv": "z", "routes/README.md": "no"})
        self.assertEqual(b.input_files(root), ["routes/schema-01/B/BAW-all.csv", "routes/schema-01/E/EZY-1.csv",
                                               "airlines/schema-01/airlines.csv"])

    def test_missing_layout_fails(self):
        with self.assertRaises(b.BuildError):
            b.input_files(self.tree({"airlines/schema-01/airlines.csv": "z"}))
        with self.assertRaises(b.BuildError):
            b.input_files(self.tree({"routes/schema-01/B/BAW-all.csv": "x"}))

    def test_content_hash_covers_paths_and_bytes(self):
        one = self.tree({"a/x.csv": "1", "a/y.csv": "2"})
        two = self.tree({"a/x.csv": "1", "a/y.csv": "3"})
        three = self.tree({"a/x.csv": "12", "a/y.csv": ""})
        hashes = {b.content_sha256(root, ["a/x.csv", "a/y.csv"]) for root in (one, two, three)}
        self.assertEqual(len(hashes), 3)


if __name__ == "__main__":
    unittest.main()
