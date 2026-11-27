"""Tests for the route map checks of the emulator smoke test: route_end.py,
corridor_pixels.py, map_pan.py, download_log.py and netstats_uid.py.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import tempfile
import unittest

import corridor_pixels
import download_log
import map_pan
import netstats_uid
import route_end
from test_route_span import HEIGHT, ROUTE, WIDTH, picture, png

AIRPORTS = "iata\ticao\tname\tcity\tcountry\tlat\tlon\tsize\ttz\tkeywords\n" \
    "LHR\tEGLL\tLondon Heathrow\tLondon\tGB\t51.4706\t-0.461941\tL\tEurope/London\t\n" \
    "CDG\tLFPG\tParis Charles de Gaulle\tParis\tFR\t49.0128\t2.55\tL\tEurope/Paris\t\n"


class Files(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, name, data):
        path = os.path.join(self.dir.name, name)
        with open(path, "wb" if isinstance(data, bytes) else "w", encoding=None if isinstance(data, bytes) else "utf-8") as f:
            f.write(data)
        return path


class RouteEndTest(Files):

    def test_the_origin_end_follows_the_airports_longitudes(self):
        image = self.write("route.png", png(picture(40, 160), alpha=True, filter_type=1))
        airports = self.write("airports.tsv", AIRPORTS)
        self.assertEqual((44, 32), route_end.end(image, west=True))
        self.assertEqual((156, 72), route_end.end(image, west=False))
        # Above y 50 the line reaches x 135 (y 30 + (x - 40) / 3 < 50 up to x 99, thickness 4).
        self.assertEqual((99 - 4, 49), route_end.end(image, west=False, max_y=50))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, route_end.main(["route_end.py", image, "LHR", "CDG", airports]))
            self.assertEqual(0, route_end.main(["route_end.py", image, "CDG", "LHR", airports]))
        self.assertEqual("44 32\n156 72\n", out.getvalue())

    def test_no_route(self):
        image = self.write("map.png", png(picture(), alpha=False, filter_type=2))
        self.assertIsNone(route_end.end(image, west=True))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, route_end.main(["route_end.py", image, "LHR", "CDG", self.write("a.tsv", AIRPORTS)]))


class CorridorPixelsTest(Files):

    def test_counts_only_the_corridor_palette(self):
        rows = picture(40, 160)  # world-map land and sea, and the route: none count
        self.assertEqual(0, corridor_pixels.count(self.write("world.png", png(rows, alpha=True, filter_type=0))))
        for y in range(10):
            for x in range(WIDTH):
                rows[y][x] = (0xE8, 0xE2, 0xD8) if x % 2 else (0xE2, 0xB1, 0x7B)  # residential; motorway off by 1
        image = self.write("corridor.png", png(rows, alpha=False, filter_type=1))
        self.assertEqual(10 * WIDTH, corridor_pixels.count(image))
        self.assertEqual(4 * WIDTH, corridor_pixels.count(image, max_y=4))
        self.assertLess(10 * WIDTH, WIDTH * HEIGHT)


class MapPanTest(unittest.TestCase):

    def test_drag_to_a_place_north_west(self):
        dx, dy = map_pan.drag(2.625, 8, (51.47, -0.45), (54.2, -1.0))
        # 0.55 degrees of longitude at 512 * 2^8 * 2.625 px per 360 degrees.
        self.assertEqual(round(0.55 / 360 * 512 * 256 * 2.625), dx)
        self.assertGreater(dy, 0)
        self.assertEqual((0, 0), map_pan.drag(2.625, 8, (10.0, 20.0), (10.0, 20.0)))
        self.assertEqual((-dx, -dy), map_pan.drag(2.625, 8, (54.2, -1.0), (51.47, -0.45)))

    def test_mercator_y_grows_southwards_and_doubles_per_zoom(self):
        _, y7 = map_pan.world_xy(0.0, 0.0, 7, 1.0)
        self.assertEqual(512 * 128 / 2, y7)
        self.assertLess(map_pan.world_xy(50.0, 0.0, 8, 1.0)[1], map_pan.world_xy(40.0, 0.0, 8, 1.0)[1])


LOG = """\
11-27 10:00:00.000  1234  1250 I Flyoff  : download-start route=LHR-CDG kind=ROUTE part=1/1 template=https://tiles.openfreemap.org/planet/20261122_124456_pt/{z}/{x}/{y}.pbf pinned=false network=UNMETERED
11-27 10:00:01.000  1234  1250 I Flyoff  : download-region id=1 route=LHR-CDG kind=ROUTE part=1/1 zooms=6-7 planned_tiles=25 required_tiles=25 required_resources=26
11-27 10:00:09.000  1234  1250 I Flyoff  : download-region-done id=1 route=LHR-CDG kind=ROUTE part=1/1 zooms=6-7 tiles=25 tile_bytes=2000000 bytes=2001000
11-27 10:00:09.100  1234  1250 I Flyoff  : download-region id=2 route=LHR-CDG kind=ROUTE part=1/1 zooms=8-8 planned_tiles=40 required_tiles=38 required_resources=39
11-27 10:00:20.000  1234  1250 I Flyoff  : download-region-done id=2 route=LHR-CDG kind=ROUTE part=1/1 zooms=8-8 tiles=38 tile_bytes=3000000 bytes=3001000
11-27 10:00:20.100  1234  1250 I Flyoff  : download-done route=LHR-CDG kind=ROUTE part=1/1 tiles=63 bytes=5002000 limit_tiles=3000 limit_bytes=50000000
11-27 10:00:20.200  1234  1300 I OtherTag: download-done route=X tiles=999999 bytes=1
"""


class DownloadLogTest(unittest.TestCase):

    def test_a_download_within_the_limits(self):
        report, problems = download_log.check(LOG)
        self.assertEqual([], problems)
        self.assertIn("download LHR-CDG ROUTE 1/1: 63 tiles, 5002000 bytes", report)
        self.assertIn("region 2 LHR-CDG 1/1 zooms 8-8: planned 40 tiles, MapLibre requires 38 tiles (39 resources)", report)
        self.assertEqual(5, len(report))

    def test_over_the_limits(self):
        report, problems = download_log.check(LOG.replace("tiles=63 bytes=5002000", "tiles=3001 bytes=5002000"))
        self.assertEqual(["download LHR-CDG ROUTE 1/1 is over the limits: 3001 tiles, 5002000 bytes"], problems)
        _, problems = download_log.check(LOG.replace("tiles=63 bytes=5002000", "tiles=63 bytes=50000001"))
        self.assertEqual(1, len(problems))

    def test_no_finished_download_or_a_stopped_one(self):
        self.assertEqual(["no finished download in the log"], download_log.check("")[1])
        stopped = "I Flyoff  : download-stopped route=LHR-CDG kind=ROUTE part=1/1 problem=OVER_LIMIT reason=TOO_MANY_TILES\n"
        self.assertIn("download LHR-CDG ROUTE 1/1 stopped: OVER_LIMIT (TOO_MANY_TILES)", download_log.check(LOG + stopped)[1])


NETSTATS = """\
Active interfaces:
  iface=wlan0 ident=[{type=WIFI, metered=false, defaultNetwork=true}]
Dev stats:
  Pending bytes: 100
UID stats:
  Pending bytes: 0
  Complete history:
  ident=[{type=WIFI, ratType=COMBINED, networkId="AndroidWifi", metered=false, defaultNetwork=true, subId=-1}] uid=10190 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=1795600800 rb=1000 rp=10 tb=200 tp=4 op=0
      st=1795608000 rb=3000 rp=20 tb=400 tp=8 op=0
  ident=[{type=MOBILE, ratType=COMBINED, metered=true, defaultNetwork=false, subId=1}] uid=10190 set=FOREGROUND tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=1795608000 rb=50 rp=1 tb=5 tp=1 op=0
  ident=[{type=WIFI, ratType=COMBINED, networkId="AndroidWifi", metered=false, defaultNetwork=true, subId=-1}] uid=10191 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=1795608000 rb=999999 rp=1 tb=1 tp=1 op=0
UID tag stats:
  Pending bytes: 0
  ident=[{type=WIFI}] uid=10190 set=DEFAULT tag=0x1
    NetworkStatsHistory: bucketDuration=7200
      st=1795608000 rb=777 rp=1 tb=1 tp=1 op=0
"""


class NetstatsUidTest(unittest.TestCase):

    def test_sums_the_uid_over_networks_and_sets(self):
        self.assertEqual(1000 + 200 + 3000 + 400 + 50 + 5, netstats_uid.total(NETSTATS, 10190))
        self.assertEqual(1000000, netstats_uid.total(NETSTATS, 10191))
        self.assertEqual(0, netstats_uid.total(NETSTATS, 10192))
        self.assertEqual(0, netstats_uid.total("not a dump", 10190))


if __name__ == "__main__":
    unittest.main()
