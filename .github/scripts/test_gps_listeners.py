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


GPS_TRACKING = """    gps provider:
      service: ProviderRequest[@+5s0ms, HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      listeners:
        10209/io.github.mahendrabishnoi2.flyoff/4E565052 Request[@+5s0ms HIGH_ACCURACY, minUpdateInterval=+5s0ms, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      enabled=true
"""

# A background app without a foreground service: flagged, and its interval raised to 30 minutes.
GPS_BACKGROUND = """    gps provider:
      service: ProviderRequest[OFF]
      listeners:
        10209/io.github.mahendrabishnoi2.flyoff/4E565052 {bg} Request[@+30m0s0ms HIGH_ACCURACY, minUpdateInterval=+30m0s0ms]
      enabled=true
"""

GPS_SLOW = GPS_TRACKING.replace("@+5s0ms HIGH_ACCURACY", "@+2m0s0ms HIGH_ACCURACY")
GPS_INACTIVE = GPS_TRACKING.replace("4E565052 Request", "4E565052 (inactive) Request")


class ThrottleTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def run_main(self, text):
        path = os.path.join(self.dir.name, "dumpsys.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gps_listeners.main(["gps_listeners.py", path, PKG, "--max-interval", "60"])
        return code, out.getvalue().strip(), err.getvalue().strip()

    def test_interval_parsing(self):
        self.assertEqual(1.0, gps_listeners.interval_seconds("x Request[@+1s0ms HIGH_ACCURACY]"))
        self.assertEqual(1800.0, gps_listeners.interval_seconds("x Request[@+30m0s0ms HIGH_ACCURACY]"))
        self.assertEqual(3725.5, gps_listeners.interval_seconds("x Request[@+1h2m5s500ms HIGH_ACCURACY]"))
        self.assertEqual(0.0, gps_listeners.interval_seconds("x Request[@0 HIGH_ACCURACY]"))
        self.assertIsNone(gps_listeners.interval_seconds("x Request[PASSIVE]"))

    def test_a_foreground_service_listener_at_5_s_passes(self):
        self.assertEqual((0, "1", ""), self.run_main(HEAD + GPS_TRACKING + TAIL))

    def test_a_background_listener_fails(self):
        code, out, err = self.run_main(HEAD + GPS_BACKGROUND + TAIL)
        self.assertEqual((3, "1"), (code, out))
        self.assertIn("background", err)

    def test_an_inactive_listener_fails(self):
        self.assertEqual(3, self.run_main(HEAD + GPS_INACTIVE + TAIL)[0])

    def test_a_listener_slower_than_the_limit_fails(self):
        code, _, err = self.run_main(HEAD + GPS_SLOW + TAIL)
        self.assertEqual(3, code)
        self.assertIn("interval 120 s", err)

    def test_no_listener_is_not_throttled_but_counts_0(self):
        # The count (0) is the caller's to check.
        self.assertEqual((0, "0"), self.run_main(HEAD + GPS_OFF + TAIL)[:2])


if __name__ == "__main__":
    unittest.main()
