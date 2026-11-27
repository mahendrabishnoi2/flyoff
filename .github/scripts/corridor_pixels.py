"""Count the pixels a downloaded corridor paints in a PNG screenshot (stdlib only).

The corridor style (app/src/main/assets/style/corridor.json) draws roads,
towns and woods in colours the bundled world style never uses. Counting
pixels within TOLERANCE of those colours tells a map that shows the corridor
from one that doesn't: the world map alone has (almost) none of them.

Usage: python3 -I corridor_pixels.py <file.png> [max-y]
Prints the count, over the rows above max-y when given (the map above the panel).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I leaves it off sys.path
from png_colours import pixels  # noqa: E402

# Motorway, trunk and primary roads, residential areas and woods: solid colours that only
# corridor.json uses (its national parks are translucent, so they're left out).
PALETTE = [(0xE2, 0xB0, 0x7C), (0xE9, 0xC9, 0xA0), (0xDD, 0xD1, 0xBD), (0xE8, 0xE2, 0xD8), (0xE4, 0xE9, 0xD4)]
TOLERANCE = 3


def count(path, max_y=None):
    n = 0
    for y, (row, ch) in enumerate(pixels(path)):
        if max_y is not None and y >= max_y:
            break
        for x in range(0, len(row), ch):
            r, g, b = row[x], row[x + 1], row[x + 2]
            for pr, pg, pb in PALETTE:
                if abs(r - pr) <= TOLERANCE and abs(g - pg) <= TOLERANCE and abs(b - pb) <= TOLERANCE:
                    n += 1
                    break
    return n


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    print(count(argv[1], int(argv[2]) if len(argv) == 3 else None))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
