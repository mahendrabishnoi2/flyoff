"""Tests for dot_colour.py on synthetic PNGs.

Run: python3 -I -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import os
import tempfile
import unittest

import dot_colour
from test_route_span import WIDTH, HEIGHT, LAND, png

LIVE = (74, 144, 226)  # #4a90e2, MapLibre's location dot
GREY = (161, 176, 192)  # #a1b0c0, the estimated dot


def picture(dot=None, size=10):
    """Land, with a [size] x [size] square of [dot] in it."""
    rows = [[LAND] * WIDTH for _ in range(HEIGHT)]
    if dot:
        for y in range(50, 50 + size):
            for x in range(80, 80 + size):
                rows[y][x] = dot
    return rows


class DotColourTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, rows, alpha=False, filter_type=0):
        path = os.path.join(self.dir.name, "shot.png")
        with open(path, "wb") as f:
            f.write(png(rows, alpha, filter_type))
        return path

    def test_counts_exact_pixels(self):
        path = self.write(picture(LIVE))
        self.assertEqual(100, dot_colour.count(path, LIVE))
        self.assertEqual(0, dot_colour.count(path, GREY, 3))

    def test_tolerance(self):
        near = (GREY[0] + 3, GREY[1] - 3, GREY[2])
        path = self.write(picture(near), alpha=True, filter_type=1)
        self.assertEqual(0, dot_colour.count(path, GREY))
        self.assertEqual(100, dot_colour.count(path, GREY, 3))
        self.assertEqual(0, dot_colour.count(path, GREY, 2))

    def test_no_dot(self):
        self.assertEqual(0, dot_colour.count(self.write(picture(), filter_type=2), LIVE, 3))

    def test_main(self):
        path = self.write(picture(GREY, 5))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = dot_colour.main(["dot_colour.py", path, "#A1B0C0", "3"])
        self.assertEqual((0, "25"), (code, out.getvalue().strip()))
        with self.assertRaises(ValueError):
            dot_colour.parse_rgb("abc")


if __name__ == "__main__":
    unittest.main()
