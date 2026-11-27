"""Tests for gps_listeners.py on `dumpsys location` excerpts.

The fixtures are cut from real API 35 emulator dumps (CI run 45943161253:
dumpsys-02-gps.txt, dumpsys-15-screen-off.txt, dumpsys-location-background.txt).
The flagged, inactive and mocked lines have no real sample yet; they are built
the way AOSP prints them (LocationProviderManager.Registration.toString(),
ListenerMultiplexer.dump() and LocationProviderManager.dump()).

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
    current users: u[0]
  Location Settings:
    Location Setting: true
    Throttling Allow Packages:
      com.google.android.gms
  Location Providers:
    passive provider:
      service: registered
      listeners:
        10143/com.google.android.gms[fused_location_provider]/BE702AC6 Request[PASSIVE, minUpdateInterval=0, hiddenFromAppOps, WorkSource{10143 com.google.android.gms}]
        1000/android[SensorNotificationService]/D918CEA4 Request[PASSIVE, minUpdateInterval=+30m0s0ms, minUpdateDistance=100000.0, WorkSource{1000 android}]
      enabled=true
      allowed=true
    network provider:
      service: ProviderRequest[@+6h0m0s0ms, WorkSource{10136 com.google.android.as}]
      listeners:
        10136/com.google.android.as/EA1F118A Request[@+6h0m0s0ms BALANCED, minUpdateDistance=100000.0, WorkSource{10136 com.google.android.as}]
        1000/android[GnssService]/C2488FD4 Request[PASSIVE, minUpdateInterval=0, hiddenFromAppOps, WorkSource{1000 android}]
      last location=null
      enabled=true
"""

GPS_ON = """    gps provider:
      service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      listeners:
        10209/io.github.mahendrabishnoi2.flyoff/F167A57F Request[@+1s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      last location=Location[gps 51.469998,-0.330000 hAcc=5.0 et=+1m14s734ms alt=10000.0 vAcc=0.5 vel=231.4998 sAcc=0.5 bear=0.0 bAcc=30.0 {Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]
      enabled=true
      allowed=true
      identity=1000/android[GnssService]
      properties=ProviderProperties[powerUsage=High, accuracy=Fine, requires=satellite, supports=[bearing,speed,altitude]]
      stationary throttled=false (not idle) (not stationary)
"""

# Another package whose name starts the same also listens to GPS.
GPS_OTHER_PACKAGE = GPS_ON.replace(
    "      last location=",
    "        10300/io.github.mahendrabishnoi2.flyoff.other/12345678 Request[@+1s0ms HIGH_ACCURACY, WorkSource{10300 io.github.mahendrabishnoi2.flyoff.other}]\n"
    "      last location=")

GPS_OFF = """    gps provider:
      service: ProviderRequest[OFF]
      last location=Location[gps 51.469998,-0.330000 hAcc=5.0 et=+1m42s736ms alt=10000.0 vAcc=0.5 vel=231.4998 sAcc=0.5 bear=0.0 bAcc=30.0 {Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]
      enabled=true
      allowed=true
      identity=1000/android[GnssService]
      properties=ProviderProperties[powerUsage=High, accuracy=Fine, requires=satellite, supports=[bearing,speed,altitude]]
"""

TAIL = """  Historical Aggregate Location Provider Data:
    gps:
      10209/io.github.mahendrabishnoi2.flyoff: min/max interval = 1s/5s, total/active/foreground duration = +3m18s7ms/+3m17s961ms/+3m17s363ms, locations = 117
  Event Log:
    11-27 05:07:02.405: gps provider +registration 10209/io.github.mahendrabishnoi2.flyoff/F167A57F -> Request[@+1s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
    11-27 05:07:02.405: gps provider request = ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
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

    def test_the_history_and_event_log_dont_count(self):
        # The app is in the history and the event log, but has no listener now.
        self.assertEqual((0, "0"), self.run_main(HEAD + GPS_OFF + TAIL)[:2])

    def test_a_package_whose_name_starts_the_same_doesnt_count(self):
        self.assertEqual((0, "1"), self.run_main(HEAD + GPS_OTHER_PACKAGE + TAIL)[:2])
        self.assertEqual((0, "1"), self.run_main(HEAD + GPS_OTHER_PACKAGE, PKG + ".other")[:2])

    def test_a_dump_without_a_gps_provider_is_an_error(self):
        # So a failed or empty dumpsys can't pass as "no listener".
        code, out, err = self.run_main(HEAD + TAIL)
        self.assertEqual(2, code)
        self.assertIn("no gps provider", err)
        self.assertEqual(2, self.run_main("")[0])

    def test_a_mocked_gps_provider_still_counts(self):
        # A test provider standing in for GPS ("cmd location providers
        # add-test-provider gps") keeps the app's registration.
        mocked = GPS_ON.replace("gps provider:", "gps provider [mock]:")
        self.assertEqual((0, "1"), self.run_main(HEAD + mocked + TAIL)[:2])


GPS_TRACKING = """    gps provider:
      service: ProviderRequest[@+5s0ms, HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      listeners:
        10209/io.github.mahendrabishnoi2.flyoff/861B5A89 Request[@+5s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]
      last location=Location[gps 51.469998,0.059998 hAcc=5.0 et=+4m55s609ms alt=10000.0 vAcc=0.5 vel=231.4998 sAcc=0.5 bear=0.0 bAcc=30.0 {Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]
      enabled=true
      allowed=true
      identity=1000/android[GnssService]
      mStarted=true   (changed +19s887ms ago)
      mFixInterval=5000
"""

LISTENER = "        10209/io.github.mahendrabishnoi2.flyoff/861B5A89 Request[@+5s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]\n"


def with_listener(line):
    return GPS_TRACKING.replace(LISTENER, line)


# The flags sit between the identity and the request; " (inactive)" follows the whole line.
GPS_BACKGROUND = with_listener(
    "        10209/io.github.mahendrabishnoi2.flyoff/861B5A89 {bg} Request[@+30m0s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}]\n")
GPS_NOT_PERMITTED = with_listener(
    "        10209/io.github.mahendrabishnoi2.flyoff/861B5A89 {bg, na} Request[@+5s0ms HIGH_ACCURACY, WorkSource{10209 io.github.mahendrabishnoi2.flyoff}] (inactive)\n")
GPS_INACTIVE = with_listener(LISTENER.rstrip("\n") + " (inactive)\n")
GPS_SLOW = GPS_TRACKING.replace("@+5s0ms HIGH_ACCURACY", "@+2m0s0ms HIGH_ACCURACY")


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

    def test_a_listener_not_permitted_fails(self):
        code, out, err = self.run_main(HEAD + GPS_NOT_PERMITTED + TAIL)
        self.assertEqual((3, "1"), (code, out))
        self.assertIn("not permitted", err)

    def test_an_inactive_listener_fails(self):
        code, out, err = self.run_main(HEAD + GPS_INACTIVE + TAIL)
        self.assertEqual((3, "1"), (code, out))
        self.assertIn("inactive", err)

    def test_a_listener_slower_than_the_limit_fails(self):
        code, _, err = self.run_main(HEAD + GPS_SLOW + TAIL)
        self.assertEqual(3, code)
        self.assertIn("interval 120 s", err)

    def test_no_listener_is_not_throttled_but_counts_0(self):
        # The count (0) is the caller's to check.
        self.assertEqual((0, "0"), self.run_main(HEAD + GPS_OFF + TAIL)[:2])


if __name__ == "__main__":
    unittest.main()
