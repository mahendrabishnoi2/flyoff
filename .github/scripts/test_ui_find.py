"""Tests for ui_find.py against hand-written uiautomator dumps.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import tempfile
import unittest

import ui_find

# Shaped like `uiautomator dump` output for a Compose screen: merged elements
# carry their words in text or content-desc, children follow their parents.
DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="io.github.mahendrabishnoi2.flyoff" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Follow me" resource-id="" class="android.view.View" package="io.github.mahendrabishnoi2.flyoff" content-desc="" checkable="true" checked="true" clickable="true" enabled="true" bounds="[700,1500][1040,1596]" />
    <node index="1" text="LHR → JFK" resource-id="" class="android.widget.TextView" package="io.github.mahendrabishnoi2.flyoff" content-desc="Flight from London Heathrow Airport to John F Kennedy International Airport" checkable="false" checked="false" clickable="false" enabled="true" bounds="[84,1650][330,1710]" />
    <node index="2" text="" resource-id="" class="android.view.View" package="io.github.mahendrabishnoi2.flyoff" content-desc="Remaining: 5,120 km" checkable="false" checked="false" clickable="false" enabled="true" bounds="[84,1800][390,1880]" />
    <node index="3" text="" resource-id="" class="android.view.View" package="io.github.mahendrabishnoi2.flyoff" content-desc="Flown: 9.5 km" checkable="false" checked="false" clickable="false" enabled="true" bounds="[400,1800][700,1880]" />
    <node index="4" text="Pause" resource-id="" class="android.widget.Button" package="io.github.mahendrabishnoi2.flyoff" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" bounds="[600,2100][852,2226]" />
    <node index="5" text="300×" resource-id="" class="android.view.View" package="io.github.mahendrabishnoi2.flyoff" content-desc="300 times real time" checkable="true" checked="false" clickable="true" enabled="true" bounds="[800,2240][1000,2340]" />
    <node index="6" text="Done" resource-id="" class="android.widget.Button" package="io.github.mahendrabishnoi2.flyoff" content-desc="" checkable="false" checked="false" clickable="true" enabled="false" bounds="[900,100][1040,200]" />
  </node>
</hierarchy>
"""

RESULTS = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="LHR" class="android.widget.EditText" content-desc="" bounds="[42,400][1038,560]" />
  <node index="1" text="" class="android.view.View" content-desc="LHR, London Heathrow Airport, London, United Kingdom" bounds="[0,600][1080,770]" />
  <node index="2" text="" class="android.view.View" content-desc="LCY, London City Airport, London, United Kingdom" bounds="[0,770][1080,940]" />
</hierarchy>
"""


class UiFindTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.dump = self.write("dump.xml", DUMP)
        self.results = self.write("results.xml", RESULTS)

    def write(self, name, text):
        path = os.path.join(self.dir.name, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = ui_find.main(["ui_find.py", *args])
            except SystemExit as e:
                code = e.code
        return code, out.getvalue().strip(), err.getvalue().strip()

    def test_has_matches_text_and_content_desc(self):
        self.assertEqual(0, self.run_main("has", self.dump, "^Follow me$")[0])
        self.assertEqual(0, self.run_main("has", self.dump, "^Remaining: ")[0])
        self.assertEqual(0, self.run_main("has", self.dump, "LHR → JFK")[0])
        self.assertEqual(1, self.run_main("has", self.dump, "Simulated position")[0])

    def test_center_of_the_first_match(self):
        self.assertEqual((0, "870 1548"), self.run_main("center", self.dump, "^Follow me$")[:2])
        self.assertEqual((0, "726 2163"), self.run_main("center", self.dump, "^Pause$")[:2])
        self.assertEqual((0, "900 2290"), self.run_main("center", self.dump, "^300 times real time$")[:2])

    def test_a_result_row_not_the_search_field(self):
        # The field also says LHR; the row is found by its description.
        self.assertEqual((0, "540 685"), self.run_main("center", self.results, "^LHR, ")[:2])

    def test_center_with_nothing_to_find_fails_clearly(self):
        code, out, err = self.run_main("center", self.dump, "^Stop$")
        self.assertEqual(1, code)
        self.assertEqual("", out)
        self.assertIn("nothing matches '^Stop$'", err)

    def test_number_reads_grouped_and_decimal_values(self):
        self.assertEqual((0, "5120"), self.run_main("number", self.dump, r"^Remaining: ([0-9.,]+) km$")[:2])
        self.assertEqual((0, "9.5"), self.run_main("number", self.dump, r"^Flown: ([0-9.,]+) km$")[:2])

    def test_number_needs_a_group(self):
        self.assertEqual(2, self.run_main("number", self.dump, "^Remaining")[0])

    def test_attr(self):
        self.assertEqual((0, "true"), self.run_main("attr", self.dump, "^Follow me$", "checked")[:2])
        self.assertEqual((0, "false"), self.run_main("attr", self.dump, "^Done$", "enabled")[:2])

    def test_a_bad_file_is_reported(self):
        bad = self.write("bad.xml", "ERROR: could not get idle state.")
        code, _, err = self.run_main("has", bad, "x")
        self.assertEqual(2, code)
        self.assertIn("not a uiautomator dump", err)

    def test_usage(self):
        self.assertEqual(2, self.run_main("frobnicate", self.dump, "x")[0])


if __name__ == "__main__":
    unittest.main()
