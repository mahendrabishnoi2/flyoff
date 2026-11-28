"""Tests for export_check.py on small GPX and KML files like the app writes.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import os
import tempfile
import unittest

import export_check

GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Flyoff" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>LHR → CDG</name></metadata>
  <wpt lat="51.47060" lon="-0.46194"><name>LHR</name></wpt>
  <wpt lat="49.00970" lon="2.54790"><name>CDG</name></wpt>
  <trk><name>LHR → CDG</name><trkseg>
    <trkpt lat="51.47060" lon="-0.46194"><ele>0.0</ele><time>2026-01-01T00:00:00Z</time></trkpt>
    <trkpt lat="51.40000" lon="-0.40000"><time>2026-01-01T00:00:05Z</time></trkpt>
  </trkseg></trk>
</gpx>
"""

KML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
<Document>
  <Placemark><name>LHR</name><Point><coordinates>-0.46194,51.47060</coordinates></Point></Placemark>
  <Placemark><name>CDG</name><Point><coordinates>2.54790,49.00970</coordinates></Point></Placemark>
  <Placemark><name>LHR → CDG</name><gx:Track>
    <altitudeMode>absolute</altitudeMode>
    <when>2026-01-01T00:00:00Z</when><when>2026-01-01T00:00:05Z</when>
    <gx:coord>-0.46194 51.47060 0.0</gx:coord><gx:coord>-0.40000 51.40000</gx:coord>
  </gx:Track></Placemark>
</Document>
</kml>
"""


class ExportCheckTest(unittest.TestCase):
    def check(self, text, suffix):
        fd, path = tempfile.mkstemp(suffix=suffix)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        self.addCleanup(os.remove, path)
        return export_check.check(path)

    def bad(self, text, suffix, message):
        with self.assertRaises(export_check.Problem) as raised:
            self.check(text, suffix)
        self.assertIn(message, str(raised.exception))

    def test_good_gpx(self):
        self.assertEqual(
            "2 track points, 2 waypoints, 2026-01-01T00:00:00Z to 2026-01-01T00:00:05Z", self.check(GPX, ".gpx"))

    def test_good_kml(self):
        self.assertEqual(
            "2 track points, 2 waypoints, 2026-01-01T00:00:00Z to 2026-01-01T00:00:05Z", self.check(KML, ".kml"))

    def test_not_xml(self):
        self.bad(GPX.replace("</gpx>", ""), ".gpx", "not well-formed")

    def test_wrong_namespace_or_version(self):
        self.bad(GPX.replace("GPX/1/1", "GPX/1/0"), ".gpx", "not GPX 1.1")
        self.bad(GPX.replace('version="1.1"', 'version="1.0"'), ".gpx", "not 1.1")
        self.bad(KML.replace("kml/2.2\"", "kml/2.1\""), ".kml", "not KML 2.2")

    def test_no_points(self):
        empty = GPX.split("<trkpt")[0] + "</trkseg></trk></gpx>"
        self.bad(empty, ".gpx", "no track points")

    def test_local_time(self):
        self.bad(GPX.replace("00:00:05Z", "00:00:05+01:00"), ".gpx", "ISO-8601 UTC")

    def test_lat_lon_swapped_in_kml(self):
        # 51.47 as a longitude is fine, but -0.46 / 2.5 swapped into latitude 100+ isn't: use a clear case.
        self.bad(KML.replace("-0.40000 51.40000", "51.40000 -100.00000"), ".kml", "out of range")

    def test_kml_counts_must_match(self):
        self.bad(KML.replace("<when>2026-01-01T00:00:05Z</when>", ""), ".kml", "1 times but 2 coordinates")

    def test_airports_missing(self):
        self.bad(GPX.replace('<wpt lat="49.00970" lon="2.54790"><name>CDG</name></wpt>', ""), ".gpx", "1 airports")


if __name__ == "__main__":
    unittest.main()
