"""Count the pixels of one colour in a PNG screenshot (stdlib only).

Usage: python3 -I dot_colour.py <file.png> <RRGGBB> [tolerance]
Prints how many pixels are within [tolerance] (default 0) of the colour in
each of red, green and blue. The smoke test uses it to tell the live location
dot (MapLibre's blue, #4a90e2) from the estimated one (Flyoff's grey,
#a1b0c0): the dot paints a few hundred pixels of its colour, the map only a
few dozen of that grey and none of that blue.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from png_colours import pixels  # noqa: E402


def count(path, rgb, tolerance=0):
    """How many pixels of the PNG at [path] are within [tolerance] of [rgb] in every channel."""
    r0, g0, b0 = rgb
    n = 0
    for row, ch in pixels(path):
        for x in range(0, len(row), ch):
            if abs(row[x] - r0) <= tolerance and abs(row[x + 1] - g0) <= tolerance and abs(row[x + 2] - b0) <= tolerance:
                n += 1
    return n


def parse_rgb(text):
    text = text.lstrip("#")
    if len(text) != 6:
        raise ValueError(f"not an RRGGBB colour: {text}")
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def main(argv):
    if len(argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 2
    print(count(argv[1], parse_rgb(argv[2]), int(argv[3]) if len(argv) == 4 else 0))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
