"""Check that a screenshot shows the whole flight route (stdlib only).

The camera should fit the route on screen: its colour (#c2185b, the app's
route line and airport dots) must reach across at least MIN_SPAN of the width
(default 0.5) and stay clear of the left and right edges. A line running off an
edge means the map is zoomed in on part of it, e.g. while "Follow me" is on.

Usage: python3 -I route_span.py <file.png> [min-span]
Prints where the route is; exits 1 if the check fails, with the reason.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I leaves it off sys.path
from png_colours import pixels  # noqa: E402

ROUTE = (194, 24, 91)
TOLERANCE = 40  # per channel; the airport labels (#7a0f3a) are further away


def route_columns(path):
    """(width, leftmost x, rightmost x, pixel count) of route-coloured pixels; x is None if none."""
    left = right = None
    count = width = 0
    r0, g0, b0 = ROUTE
    for row, ch in pixels(path):
        width = len(row) // ch
        for x in range(width):
            i = x * ch
            if abs(row[i] - r0) <= TOLERANCE and abs(row[i + 1] - g0) <= TOLERANCE and abs(row[i + 2] - b0) <= TOLERANCE:
                count += 1
                left = x if left is None else min(left, x)
                right = x if right is None else max(right, x)
    return width, left, right, count


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = argv[1]
    min_span = float(argv[2]) if len(argv) > 2 else 0.5
    width, left, right, count = route_columns(path)
    if left is None or count < max(20, width // 10):
        print(f"{path}: no route on screen ({count} route-coloured pixels)", file=sys.stderr)
        return 1
    print(f"route from {100 * left / width:.0f}% to {100 * right / width:.0f}% of the width ({count} pixels)")
    edge = max(1, width // 100)
    if left < edge:
        print(f"{path}: the route runs off the left edge, so it isn't all on screen", file=sys.stderr)
        return 1
    if right >= width - edge:
        print(f"{path}: the route runs off the right edge, so it isn't all on screen", file=sys.stderr)
        return 1
    span = (right - left) / width
    if span < min_span:
        print(f"{path}: the route spans only {100 * span:.0f}% of the width, expected {100 * min_span:.0f}%", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
