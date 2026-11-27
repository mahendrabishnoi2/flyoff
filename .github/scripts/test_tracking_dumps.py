"""Tests for tracking_dumps.py on dumpsys excerpts written to look like API 35's.

The excerpts are from memory of the AOSP formats (ActiveServices / ServiceRecord.dump and
NotificationRecord.dump), not captured from a device: CI's emulator run is what confirms them.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import tempfile
import unittest

import tracking_dumps

PKG = "io.github.mahendrabishnoi2.flyoff"
SERVICE = "FlightTrackingService"

SERVICES_HEAD = """ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
"""

FOREGROUND = """  * ServiceRecord{9f3d1c2 u0 io.github.mahendrabishnoi2.flyoff/.tracking.FlightTrackingService c:io.github.mahendrabishnoi2.flyoff}
    intent={act=io.github.mahendrabishnoi2.flyoff.action.START_TRACKING cmp=io.github.mahendrabishnoi2.flyoff/.tracking.FlightTrackingService}
    packageName=io.github.mahendrabishnoi2.flyoff
    processName=io.github.mahendrabishnoi2.flyoff
    permission=null
    app=ProcessRecord{b1c0d2e 12345:io.github.mahendrabishnoi2.flyoff/u0a209}
    allowWhileInUsePermissionInFgs=true
    startForegroundCount=1
    isForeground=true foregroundId=1 types=0x00000008 foregroundNoti=Notification(channel=flight_tracking shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0x00000000 actions=1 vis=PRIVATE)
    createTime=-12s345ms startingBgTimeout=--
    lastActivity=-12s300ms restartTime=-12s300ms createdFromFg=true
    startRequested=true delayedStop=false stopIfKilled=true callStart=true lastStartId=1
"""

OTHER_APP = """  * ServiceRecord{1234567 u0 com.example.other/.tracking.FlightTrackingService}
    isForeground=true foregroundId=3 types=0x00000008
"""


class ServiceTest(unittest.TestCase):

    def state(self, text):
        return tracking_dumps.service_state(text.splitlines(True), PKG, SERVICE)

    def test_a_location_foreground_service(self):
        self.assertEqual("foreground-location", self.state(SERVICES_HEAD + FOREGROUND))

    def test_the_older_field_name(self):
        older = FOREGROUND.replace("types=0x00000008", "foregroundServiceType=0x00000008")
        self.assertEqual("foreground-location", self.state(SERVICES_HEAD + older))

    def test_another_type(self):
        self.assertEqual("foreground", self.state(SERVICES_HEAD + FOREGROUND.replace("types=0x00000008", "types=0x00000001")))

    def test_started_but_not_foreground(self):
        self.assertEqual("started", self.state(SERVICES_HEAD + FOREGROUND.replace("isForeground=true", "isForeground=false")))

    def test_absent(self):
        self.assertEqual("absent", self.state(SERVICES_HEAD + "    (nothing)\n"))

    def test_another_apps_service_doesnt_count(self):
        self.assertEqual("absent", self.state(SERVICES_HEAD + OTHER_APP))

    def test_only_its_own_block_counts(self):
        # The service isn't foreground; a foreground service listed after it mustn't make it so.
        started = FOREGROUND.replace("isForeground=true", "isForeground=false")
        self.assertEqual("started", self.state(SERVICES_HEAD + started + OTHER_APP))

    def test_not_a_services_dump(self):
        self.assertIsNone(self.state(""))
        self.assertIsNone(self.state("Can't find service: activity\n"))


NOTIFICATIONS = """Current Notification Manager state:
  Notification List:
    NotificationRecord(0x0e1a2b3c: pkg=io.github.mahendrabishnoi2.flyoff user=UserHandle{0} id=1 tag=null importance=2 key=0|io.github.mahendrabishnoi2.flyoff|1|null|10209: Notification(channel=flight_tracking shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0x00000000 actions=1 vis=PRIVATE))
      uid=10209 userId=0
      opPkg=io.github.mahendrabishnoi2.flyoff
      icon=Icon(typ=RESOURCE pkg=io.github.mahendrabishnoi2.flyoff id=0x7f080004)
      flags=ONGOING_EVENT|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE
      notification=
        extras={
          android.title=String (Tracking LHR → JFK)
          android.text=String (Waiting for GPS…)
        }
    NotificationRecord(0x0aaaaaaa: pkg=com.android.systemui user=UserHandle{0} id=7 tag=null importance=2 key=0|com.android.systemui|7|null|10100: Notification(channel=BAT shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x8 color=0x00000000 vis=PRIVATE))
      uid=10100 userId=0
      notification=
        extras={
          android.title=String (Tracking LHR → JFK)
        }
"""

EMPTY = """Current Notification Manager state:
  Notification List:
"""


class NotificationTest(unittest.TestCase):

    def count(self, text, pattern=None, channel="flight_tracking"):
        return tracking_dumps.notifications(text.splitlines(True), PKG, channel, pattern)

    def test_counts_the_tracking_notification(self):
        self.assertEqual(1, self.count(NOTIFICATIONS))

    def test_with_its_title(self):
        self.assertEqual(1, self.count(NOTIFICATIONS, "Tracking LHR → JFK"))

    def test_a_title_in_another_record_doesnt_count(self):
        self.assertEqual(0, self.count(NOTIFICATIONS, "Tracking SYD → LAX"))
        # systemui's record has the same title, but it isn't ours.
        systemui_only = EMPTY + NOTIFICATIONS[NOTIFICATIONS.index("    NotificationRecord(0x0aaaaaaa"):]
        self.assertEqual(0, self.count(systemui_only, "Tracking LHR", channel="BAT"))

    def test_another_channel(self):
        self.assertEqual(0, self.count(NOTIFICATIONS, channel="flight"))

    def test_none_posted(self):
        self.assertEqual(0, self.count(EMPTY))

    def test_not_a_notification_dump(self):
        self.assertIsNone(self.count(""))


class MainTest(unittest.TestCase):

    def run_main(self, args, text):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "dump.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = tracking_dumps.main(["tracking_dumps.py", args[0], path] + args[1:])
            return code, out.getvalue().strip()

    def test_service(self):
        self.assertEqual((0, "foreground-location"), self.run_main(["service", PKG, SERVICE], SERVICES_HEAD + FOREGROUND))

    def test_notification(self):
        self.assertEqual((0, "1"), self.run_main(["notification", PKG, "flight_tracking", "LHR → JFK"], NOTIFICATIONS))

    def test_bad_dumps_exit_2(self):
        self.assertEqual(2, self.run_main(["service", PKG, SERVICE], "")[0])
        self.assertEqual(2, self.run_main(["notification", PKG, "flight_tracking"], "")[0])


if __name__ == "__main__":
    unittest.main()
