"""Find the flight's origin airport on a screenshot of the route (stdlib only).

The route line and its airport dots are #c2185b (see route_span.py). The
origin is the route's west end when it lies west of the destination, else its
east end (routes across the antimeridian aren't handled). Prints "x y", a few
pixels in from the route pixel furthest that way, for `adb shell input tap`.

Usage: python3 -I route_end.py <file.png> <from-iata> <to-iata> <airports.tsv> [max-y]
Only rows above max-y count, when given (the map above the panel). Exits 1 when
the screenshot has no route.
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I leaves it off sys.path
from png_colours import pixels  # noqa: E402
from route_span import ROUTE, TOLERANCE  # noqa: E402

INSET = 4


def longitude(airports, iata):
    with open(airports, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["iata"] == iata:
                return float(row["lon"])
    raise SystemExit(f"{iata} is not in {airports}")


def end(path, west, max_y=None):
    """(x, y) a few pixels in from the westmost (or eastmost) route pixel, the middle one of that column; None if none."""
    best_x, ys = None, []
    r0, g0, b0 = ROUTE
    for y, (row, ch) in enumerate(pixels(path)):
        if max_y is not None and y >= max_y:
            break
        for x in range(len(row) // ch):
            i = x * ch
            if abs(row[i] - r0) <= TOLERANCE and abs(row[i + 1] - g0) <= TOLERANCE and abs(row[i + 2] - b0) <= TOLERANCE:
                if best_x is None or (x < best_x if west else x > best_x):
                    best_x, ys = x, [y]
                elif x == best_x:
                    ys.append(y)
    if best_x is None:
        return None
    return best_x + (INSET if west else -INSET), ys[len(ys) // 2]


def main(argv):
    if len(argv) not in (5, 6):
        print(__doc__, file=sys.stderr)
        return 2
    png, origin, destination, airports = argv[1:5]
    max_y = int(argv[5]) if len(argv) == 6 else None
    found = end(png, west=longitude(airports, origin) < longitude(airports, destination), max_y=max_y)
    if found is None:
        print(f"no route in {png}", file=sys.stderr)
        return 1
    print("%d %d" % found)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
