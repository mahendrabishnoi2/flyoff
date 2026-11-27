"""Tests for route_span.py on synthetic PNGs.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import struct
import tempfile
import unittest
import zlib

import route_span

LAND = (242, 239, 233)
SEA = (166, 203, 227)
ROUTE = (194, 24, 91)  # #c2185b, the app's route colour
LABEL = (122, 15, 58)  # #7a0f3a, the airport labels: not the route
WIDTH, HEIGHT = 200, 120


def png(rows, alpha, filter_type):
    """Encode rows of (r, g, b) as an 8-bit RGB or RGBA PNG, every row with one filter."""
    channels = 4 if alpha else 3
    raw = bytearray()
    prev = bytes(WIDTH * channels)
    for row in rows:
        line = bytes(v for px in row for v in (px + (255,) if alpha else px))
        out = bytearray(line)
        if filter_type == 1:  # Sub
            for x in range(channels, len(line)):
                out[x] = (line[x] - line[x - channels]) & 255
        elif filter_type == 2:  # Up
            for x in range(len(line)):
                out[x] = (line[x] - prev[x]) & 255
        raw += bytes([filter_type]) + out
        prev = line

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6 if alpha else 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")


def picture(line_from=None, line_to=None, colour=ROUTE):
    """A map-like picture, with a 4-pixel-thick sloping line between two x positions."""
    rows = [[SEA if (x * 7 + y * 3) % 50 < 20 else LAND for x in range(WIDTH)] for y in range(HEIGHT)]
    if line_from is not None:
        for x in range(line_from, line_to + 1):
            y0 = 30 + (x - line_from) * 40 // max(1, line_to - line_from)
            for y in range(y0, y0 + 4):
                rows[y][x] = colour
    return rows


class RouteSpanTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def check(self, rows, alpha=True, filter_type=0, *extra):
        path = os.path.join(self.dir.name, "shot.png")
        with open(path, "wb") as f:
            f.write(png(rows, alpha, filter_type))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = route_span.main(["route_span.py", path, *extra])
            except SystemExit as e:
                code = e.code
        return code, out.getvalue() + err.getvalue()

    def test_a_fitted_route_passes(self):
        code, text = self.check(picture(20, 180))
        self.assertEqual(0, code, text)
        self.assertIn("10%", text)
        self.assertIn("90%", text)

    def test_every_filter_and_rgb_decode_the_same(self):
        for alpha in (True, False):
            for filter_type in (0, 1, 2):
                with self.subTest(alpha=alpha, filter_type=filter_type):
                    self.assertEqual(0, self.check(picture(20, 180), alpha, filter_type)[0])

    def test_a_short_route_fails(self):
        # Zoomed out too far, or only part of the route drawn.
        code, text = self.check(picture(60, 140))
        self.assertEqual(1, code)
        self.assertIn("spans only 40%", text)

    def test_a_route_running_off_the_screen_fails(self):
        # What "Follow me" gives: zoomed in on the plane, the line leaves the screen.
        code, text = self.check(picture(0, 90))
        self.assertEqual(1, code)
        self.assertIn("runs off the left edge", text)
        code, text = self.check(picture(30, WIDTH - 1))
        self.assertEqual(1, code)
        self.assertIn("runs off the right edge", text)

    def test_no_route_fails(self):
        code, text = self.check(picture())
        self.assertEqual(1, code)
        self.assertIn("no route", text)

    def test_other_reds_are_not_the_route(self):
        code, text = self.check(picture(20, 180, colour=LABEL))
        self.assertEqual(1, code)
        self.assertIn("no route", text)

    def test_the_minimum_span_can_be_set(self):
        self.assertEqual(0, self.check(picture(60, 140), True, 0, "0.3")[0])

    def test_not_a_png(self):
        path = os.path.join(self.dir.name, "bad.png")
        with open(path, "wb") as f:
            f.write(b"ERROR: no screen")
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as e:
            route_span.main(["route_span.py", path])
        self.assertIn("not a PNG", str(e.exception.code))


if __name__ == "__main__":
    unittest.main()
