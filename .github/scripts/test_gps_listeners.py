"""Tests for gps_listeners.py on `dumpsys location` excerpts (shaped like API 35's).

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import tempfile
import unittest

import gps_listeners

PKG = "io.github.mahendrabishnoi2.flyoff"

HEAD = """Location Manager State:
  User Info:
    current user: 0
  Location Providers:
    passive provider:
      listeners:
        10143/com.google.android.gms[fused_location_provider]/83F9E96E Request[PASSIVE, minUpdateInterval=0, WorkSource{10143 com.google.android.gms}]
        10209/io.github.mahendrabishnoi2.flyoff/11111111 Request[PASSIVE, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      enabled=true
"""

GPS_ON = """    gps provider:
      service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      listeners:
        10209/io.github.mahendrabishnoi2.flyoff/4E565052 Request[@+1s0ms HIGH_ACCURACY, minUpdateInterval=+1s0ms, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
        10300/io.github.mahendrabishnoi2.flyoff.other/12345678 Request[@+1s0ms HIGH_ACCURACY]
      enabled=true
      properties=ProviderProperties[powerUsage=High, accuracy=Fine, requires=satellite]
"""

GPS_OTHER_ONLY = """    gps provider:
      service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{1000 android}]
      listeners:
        1000/android[GnssService]/025F1576 Request[@+1s0ms HIGH_ACCURACY]
      enabled=true
"""

GPS_OFF = """    gps provider:
      service: ProviderRequest[OFF]
      enabled=true
"""

TAIL = """  Historical Aggregate Location Provider Data:
    gps provider:
      10209/io.github.mahendrabishnoi2.flyoff: min/max interval = 1s/1s, locations = 44
  Event Log:
    11-27 01:46:14.606: gps provider +registration 10209/io.github.mahendrabishnoi2.flyoff/AB8C61BF -> Request[@+1s0ms HIGH_ACCURACY]
"""


class GpsListenersTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def run_main(self, text, package=PKG):
        path = os.path.join(self.dir.name, "dumpsys.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gps_listeners.main(["gps_listeners.py", path, package])
        return code, out.getvalue().strip(), err.getvalue().strip()

    def test_counts_the_apps_gps_listener(self):
        self.assertEqual((0, "1"), self.run_main(HEAD + GPS_ON + TAIL)[:2])

    def test_other_apps_and_providers_and_the_history_dont_count(self):
        # The app is a passive listener, is in the history and the event log, and a
        # package whose name starts the same listens to GPS: none of that counts.
        self.assertEqual((0, "0"), self.run_main(HEAD + GPS_OTHER_ONLY + TAIL)[:2])

    def test_no_listeners_section_is_none(self):
        self.assertEqual((0, "0"), self.run_main(HEAD + GPS_OFF + TAIL)[:2])

    def test_another_package(self):
        self.assertEqual((0, "1"), self.run_main(HEAD + GPS_ON, "io.github.mahendrabishnoi2.flyoff.other")[:2])

    def test_a_dump_without_a_gps_provider_is_an_error(self):
        # So a failed or empty dumpsys can't pass as "no listener".
        code, out, err = self.run_main(HEAD + TAIL)
        self.assertEqual(2, code)
        self.assertIn("no gps provider", err)
        self.assertEqual(2, self.run_main("")[0])


if __name__ == "__main__":
    unittest.main()
