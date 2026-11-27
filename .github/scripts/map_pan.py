"""How far to drag a MapLibre map to move its view from one place to another (stdlib only).

MapLibre's world is 512 density-independent pixels wide at zoom 0 and doubles
with each zoom. Dragging the map by (dx, dy) screen pixels brings the place
(dx, dy) up-left of the screen point into it, so to go from <from> to <to> the
drag is the Web Mercator offset of <from> from <to>.

Usage: python3 -I map_pan.py <density> <zoom> <from-lat> <from-lon> <to-lat> <to-lon>
Prints "dx dy" in screen pixels (positive: right and down).
"""
import math
import sys


def world_xy(lat, lon, zoom, density):
    size = 512 * 2 ** zoom * density
    x = (lon + 180) / 360 * size
    y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * size
    return x, y


def drag(density, zoom, start, target):
    x0, y0 = world_xy(*start, zoom, density)
    x1, y1 = world_xy(*target, zoom, density)
    return round(x0 - x1), round(y0 - y1)


def main(argv):
    if len(argv) != 7:
        print(__doc__, file=sys.stderr)
        return 2
    density, zoom, a, b, c, d = map(float, argv[1:])
    print("%d %d" % drag(density, zoom, (a, b), (c, d)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
